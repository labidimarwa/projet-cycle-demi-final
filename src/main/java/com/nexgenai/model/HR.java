package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Entity
@DiscriminatorValue("HR")
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class HR extends User {
    
    @Column(name = "department")
    private String department;
    
    @Column(name = "position")
    private String position;
    


    @Override
    @Transient
    public String getUserType() {
        return "HR";
    }
}