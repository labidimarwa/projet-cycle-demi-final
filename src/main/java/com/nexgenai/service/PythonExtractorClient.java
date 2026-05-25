package com.nexgenai.service;

import com.nexgenai.dto.matching.CvExtractionResult;
import com.nexgenai.dto.matching.EmbedRequest;
import com.nexgenai.dto.matching.EmbedResult;
import com.nexgenai.dto.matching.EscoNormalizationRequest;
import com.nexgenai.dto.matching.EscoNormalizationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import org.springframework.core.ParameterizedTypeReference;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Client HTTP vers le microservice Python d'extraction CV (port 8000).
 *
 * Contrainte 4 : si Python est indisponible, retourne une RuntimeException
 * avec un message métier clair — jamais de NullPointerException.
 * Contrainte 5 : tous les embeddings viennent de Python (JobBERTa).
 *               Spring Boot ne fait que la similarité cosinus.
 */
@Service
@Slf4j
public class PythonExtractorClient {

    private final WebClient webClient;

    @Value("${matching.python.timeout:30000}")
    private long timeoutMs;

    public PythonExtractorClient(
            @Value("${matching.python.url:http://localhost:8000}") String url) {
        this.webClient = WebClient.builder()
            .baseUrl(url)
            // Max 10 Mo pour accepter les gros CVs avec embeddings (vecteurs 768d × N skills)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    }

    /**
     * Envoie le CV au microservice Python pour extraction + calcul d'embeddings.
     *
     * @param cvBytes   contenu binaire du fichier CV
     * @param filename  nom original (détecte l'extension PDF / DOCX)
     * @return entités extraites avec embeddings JobBERTa
     * @throws RuntimeException message métier si Python inaccessible
     */
    public CvExtractionResult extraireCv(byte[] cvBytes, String filename) {
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("fichier", new ByteArrayResource(cvBytes) {
            @Override
            public String getFilename() { return filename; }
        }).contentType(
            filename.toLowerCase().endsWith(".pdf")
                ? MediaType.APPLICATION_PDF
                : MediaType.APPLICATION_OCTET_STREAM
        );

        try {
            CvExtractionResult result = webClient.post()
                .uri("/extract")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form.build()))
                .retrieve()
                .onStatus(s -> s.is4xxClientError(), resp ->
                    resp.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new RuntimeException("CV invalide : " + body)))
                )
                .bodyToMono(CvExtractionResult.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .block();

            if (result == null) throw new RuntimeException("Réponse vide du service d'extraction.");
            return result;

        } catch (WebClientRequestException e) {
            // Connexion refusée : Python pas démarré
            log.error("❌ Microservice Python inaccessible ({})", e.getMessage());
            throw new RuntimeException(
                "Le service d'extraction IA est indisponible. " +
                "Démarrez le microservice Python : uvicorn main:app --port 8000"
            );
        } catch (WebClientResponseException e) {
            log.error("❌ Erreur Python {} : {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Erreur extraction CV : " + e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            // Re-throw les erreurs métier déjà formatées
            throw e;
        } catch (Exception e) {
            log.error("❌ Erreur inattendue extraction : {}", e.getMessage());
            throw new RuntimeException("Erreur inattendue lors de l'extraction : " + e.getMessage());
        }
    }

    /**
     * Calcule les embeddings JobBERTa pour une liste de termes (noms des skills du poste).
     * Spring Boot appelle cela pour obtenir les vecteurs des compétences requises
     * et ensuite calculer la similarité cosinus contre les embeddings du CV.
     *
     * @param termes noms des compétences requises par le poste
     * @return map terme → vecteur d'embedding
     */
    public EmbedResult calculerEmbeddings(List<String> termes) {
        if (termes == null || termes.isEmpty()) {
            EmbedResult vide = new EmbedResult();
            vide.setEmbeddings(java.util.Map.of());
            return vide;
        }

        try {
            EmbedResult result = webClient.post()
                .uri("/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new EmbedRequest(termes))
                .retrieve()
                .bodyToMono(EmbedResult.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .block();

            if (result == null) {
                EmbedResult vide = new EmbedResult();
                vide.setEmbeddings(java.util.Map.of());
                return vide;
            }
            return result;

        } catch (WebClientRequestException e) {
            log.error("❌ Python /embed inaccessible");
            throw new RuntimeException(
                "Le service d'embeddings est indisponible (port 8000)."
            );
        }
    }

    /**
     * Normalise une liste de noms de compétences bruts contre ESCO v1.2.
     * Appelle POST /normalize du microservice Python enhanced.
     *
     * Cette méthode est ADDITIVE et TOLÉRANTE : si le service Python ne supporte
     * pas encore /normalize (old main.py), elle retourne un résultat vide sans
     * lever d'exception. Le matching continue sans ESCO.
     *
     * @param skills noms bruts à normaliser (ex : ["JS", "Machine Learning"])
     * @return résultat ESCO avec URI canoniques + labels préférés + confiances
     */
    public EscoNormalizationResult normaliserSkills(List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return emptyNormalization();
        }
        try {
            EscoNormalizationResult result = webClient.post()
                .uri("/normalize")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new EscoNormalizationRequest(skills))
                .retrieve()
                .bodyToMono(EscoNormalizationResult.class)
                .timeout(Duration.ofMillis(10_000))
                .block();
            return result != null ? result : emptyNormalization();
        } catch (Exception e) {
            log.warn("⚠️ ESCO normalisation indisponible — matching continue sans ESCO : {}", e.getMessage());
            return emptyNormalization();
        }
    }

    private EscoNormalizationResult emptyNormalization() {
        EscoNormalizationResult r = new EscoNormalizationResult();
        r.setNormalized(Collections.emptyList());
        return r;
    }

    /**
     * Retourne les suggestions ESCO pour un terme partiel (autocomplete Angular).
     * Appelle GET /esco/skills?q=&limit= du microservice Python enhanced.
     * Retourne une liste vide (sans exception) si le service ne supporte pas ce endpoint.
     *
     * @param query terme de recherche (ex : "java", "mac")
     * @param limit nombre maximum de suggestions
     * @return liste de suggestions ESCO [{esco_uri, preferred_label, confidence, alt_labels}]
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> suggestEscoSkills(String query, int limit) {
        try {
            Map<String, Object> response = webClient.get()
                .uri(uri -> uri.path("/esco/skills")
                    .queryParam("q", query)
                    .queryParam("limit", limit)
                    .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(5))
                .block();
            if (response == null) return Collections.emptyList();
            Object suggestions = response.get("suggestions");
            return suggestions instanceof List ? (List<Map<String, Object>>) suggestions
                                               : Collections.emptyList();
        } catch (Exception e) {
            log.debug("ESCO suggest indisponible pour '{}' : {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Vérifie que le microservice Python répond sur GET /health.
     * Utilisé avant de lancer le matching pour donner une erreur claire.
     */
    public boolean isAvailable() {
        try {
            webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(3))
                .block();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
