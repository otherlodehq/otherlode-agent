package com.example.target.jpa.ebean;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class EbeanNamed {
  @Id
  private Long id;
  private String name;

  public Long getId() { return id; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  @Override
  public String toString() {
    return name == null ? "unnamed" : "Named " + name;
  }
}
