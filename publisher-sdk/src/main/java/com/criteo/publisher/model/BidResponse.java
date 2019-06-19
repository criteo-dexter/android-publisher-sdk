package com.criteo.publisher.model;

public class BidResponse {

    private double price;
    private BidToken token;
    private boolean valid;

    public BidResponse(double price, BidToken token, boolean valid) {
        this.price = price;
        this.token = token;
        this.valid = valid;
    }

    public double getPrice() {
        return price;
    }

    public BidToken getToken() {
        return token;
    }

    public boolean isValid() {
        return valid;
    }
}