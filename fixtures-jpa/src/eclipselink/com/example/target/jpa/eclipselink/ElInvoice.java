package com.example.target.jpa.eclipselink;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

@Entity
public class ElInvoice {
  private Long key;
  private ElAccount acct;

  @Id
  public Long getId() { return key; }

  public void setId(Long id) {
    if (id != null && id < 0) {
      throw new IllegalArgumentException("negative id");
    }
    this.key = id;
  }

  @ManyToOne(fetch = FetchType.LAZY)
  public ElAccount getAccount() { return acct; }

  public void setAccount(ElAccount account) { this.acct = account; }
}
