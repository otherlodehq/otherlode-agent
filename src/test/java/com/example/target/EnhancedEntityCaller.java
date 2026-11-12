package com.example.target;

public class EnhancedEntityCaller {
    public void assign(EnhancedEntityTarget entity, EnhancedAssociationTarget order) {
        entity.$$_hibernate_write_order(order);
    }
}
