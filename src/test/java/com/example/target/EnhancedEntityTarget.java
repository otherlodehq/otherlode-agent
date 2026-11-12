package com.example.target;

public class EnhancedEntityTarget {
    private String name;
    private EnhancedAssociationTarget order;
    private Object interceptor;

    public String getName() {
        return $$_hibernate_read_name();
    }

    public void setOrder(EnhancedAssociationTarget order) {
        $$_hibernate_write_order(order);
    }

    public String $$_hibernate_read_name() {
        if (interceptor != null) {
            return String.valueOf(interceptor);
        }
        return name;
    }

    public void $$_hibernate_write_order(EnhancedAssociationTarget order) {
        this.order = order;
        if (order != null) {
            order.addItem(this);
        }
    }

    public void $$_hibernate_trackChange(String attribute) {
        if (attribute == null) {
            return;
        }
        name = attribute;
    }
}
