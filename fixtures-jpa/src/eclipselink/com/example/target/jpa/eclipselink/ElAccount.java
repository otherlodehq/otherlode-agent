package com.example.target.jpa.eclipselink;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class ElAccount {
  @Id
  private Long id;
  private String owner;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getOwner() { return owner; }
  public void setOwner(String owner) { this.owner = owner; }
}
