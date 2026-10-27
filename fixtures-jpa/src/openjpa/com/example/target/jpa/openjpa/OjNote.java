package com.example.target.jpa.openjpa;

import com.example.outside.HasNote;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class OjNote implements HasNote {
  private Long id;
  private String note;

  @Id
  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  @Override
  public String getNote() { return note; }
  public void setNote(String note) { this.note = note; }
}
