package com.example.target.jpa.openjpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class OjAccount {
  private Long id;
  private String owner;

  @Id
  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getOwner() { return owner; }

  public void setOwner(String owner) {
    if (owner != null && owner.isEmpty()) {
      throw new IllegalArgumentException("empty owner");
    }
    this.owner = owner;
  }
}
