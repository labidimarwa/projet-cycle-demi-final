package com.nexgenai.model;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import jakarta.persistence.*;
@Entity
@DiscriminatorValue("ADMIN")
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class Admin extends User {
    
    @Column(name = "role_level")
    private String roleLevel = "SUPER_ADMIN";

    
    @Override
    @Transient
    public String getUserType() {
        return "ADMIN";
    }
}