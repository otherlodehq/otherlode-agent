package com.example.target.jpa.openjpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class OjCustomer {
  @Id
  private Long id;
  private String name;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String pcRate() { return name == null ? "none" : name; }
}
