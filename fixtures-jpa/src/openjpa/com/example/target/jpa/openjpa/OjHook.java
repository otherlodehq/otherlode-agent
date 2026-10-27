package com.example.target.jpa.openjpa;

import com.example.outside.Hook;
import com.example.outside.HookClass;
import com.example.outside.Tagged;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class OjHook implements Tagged {
  private Long id;
  private String note;
  private String tag;
  private String label;
  private String plain;

  @Id
  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  @Hook
  public String getNote() { return note; }
  public void setNote(String note) { this.note = note; }
  public String getTag() { return tag; }
  public void setTag(String tag) { this.tag = tag; }
  @HookClass
  public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }
  public String getPlain() { return plain; }
  public void setPlain(String plain) { this.plain = plain; }
}
