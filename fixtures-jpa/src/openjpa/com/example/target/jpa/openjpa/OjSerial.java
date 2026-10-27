package com.example.target.jpa.openjpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.io.Serializable;

@Entity
public class OjSerial implements Serializable {
  @Id
  private Long id;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
}
