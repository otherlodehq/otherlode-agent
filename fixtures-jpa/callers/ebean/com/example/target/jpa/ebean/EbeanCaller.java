package com.example.target.jpa.ebean;

public class EbeanCaller {
  public Object copy(EbeanCustomer customer) {
    return customer._ebean_newInstance();
  }

  public String[] names(EbeanCustomer customer) {
    return customer._ebean_getPropertyNames();
  }
}
