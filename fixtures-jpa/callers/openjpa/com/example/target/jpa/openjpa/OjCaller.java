package com.example.target.jpa.openjpa;

public class OjCaller {
  public Object create(OjAccount account) {
    return account.pcNewInstance(null, true);
  }

  public Object rename(OjAccount account, String owner) {
    account.setOwner(owner);
    return account.getOwner();
  }
}
