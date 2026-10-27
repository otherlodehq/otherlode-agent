package com.example.target.jpa.openjpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class OjLabel {
  private Long id;
  private String label;

  @Id
  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }

  public String getLabel() {
    return label == null ? "none" : label;
  }

  public void setLabel(String label) {
    this.label = label == null ? "" : label;
  }
}
