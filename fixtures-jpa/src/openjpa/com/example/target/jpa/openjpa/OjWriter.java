package com.example.target.jpa.openjpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;

@Entity
public class OjWriter implements Serializable {
  @Id
  private Long id;
  private transient boolean flushed;
  private transient int mode;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }

  private void writeObject(ObjectOutputStream out) throws IOException {
    out.defaultWriteObject();
    switch (mode) {
      case 1:
        return;
      case 2:
        flushed = false;
        break;
      default:
        break;
    }
    if (id == null) {
      flushed = true;
    }
  }
}
