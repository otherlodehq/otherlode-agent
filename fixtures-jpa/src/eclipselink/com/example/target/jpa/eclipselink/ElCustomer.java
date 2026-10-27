package com.example.target.jpa.eclipselink;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class ElCustomer {
  @Id
  private Long id;
  private String name;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String label() { return name == null ? "none" : name; }
  public String _persistence_custom() { return name == null ? "custom" : name; }
}
