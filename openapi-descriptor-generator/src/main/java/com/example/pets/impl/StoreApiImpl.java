package com.example.pets.impl;

import akka.NotUsed;
import com.example.auction.item.api.StoreApi;
import com.lightbend.lagom.javadsl.api.ServiceCall;

import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

import static java.util.concurrent.CompletableFuture.completedFuture;

@Singleton
public class StoreApiImpl implements StoreApi {

    @Override
    public ServiceCall<NotUsed, NotUsed> deleteOrder(Long orderId) {
        System.out.println("deleteOrder");
        return notUsed -> completedFuture(NotUsed.getInstance());
    }

    @Override
    public ServiceCall<NotUsed, Map<String, Integer>> getInventory() {
        System.out.println("getInventory");
        return notUsed -> completedFuture(new HashMap<String, Integer>());
    }

    @Override
    ServiceCall<NotUsed, Order> getOrderById(Long orderId) {
        System.out.println("getOrderById");
        return notUsed -> completedFuture(Order);
    }

    @Override
    public ServiceCall<Order, Order> placeOrder() {
        System.out.println("placeOrder");
        return order -> completedFuture(Order);
    }
}