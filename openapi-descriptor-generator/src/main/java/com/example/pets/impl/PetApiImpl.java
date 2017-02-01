package com.example.pets.impl;

import akka.NotUsed;
import com.example.auction.item.api.PetApi;
import com.lightbend.lagom.javadsl.api.ServiceCall;

import javax.inject.Singleton;

import static java.util.concurrent.CompletableFuture.completedFuture;

@Singleton
public class PetApiImpl implements PetApi {

    @Override
    public ServiceCall<Pet, NotUsed> addPet() {
        System.out.println("addPet");
        return pet -> completedFuture(NotUsed.getInstance());
    }

    @Override
    public ServiceCall<NotUsed, NotUsed> deletePet(Long petId) {
        System.out.println("deletePet");
        return notUsed -> completedFuture(NotUsed.getInstance());
    }

    @Override
    public ServiceCall<NotUsed, List<Pet>> findPetsByStatus(List<String> status) {
        System.out.println("findPetsByStatus");
        return notUsed -> completedFuture(new ArrayList<Pet>());
    }

    @Override
    public ServiceCall<NotUsed, List<Pet>> findPetsByTags(List<String> tags) {
        System.out.println("findPetsByTags");
        return notUsed -> completedFuture(new ArrayList<Pet>());
    }

    @Override
    public ServiceCall<NotUsed, Pet> getPetById(Long petId) {
        System.out.println("getPetById");
        return notUsed -> completedFuture(Pet);
    }

    @Override
    public ServiceCall<Pet, NotUsed> updatePet() {
        System.out.println("updatePet");
        return pet -> completedFuture(NotUsed.getInstance());
    }

    @Override
    public ServiceCall<NotUsed, NotUsed> updatePetWithForm(Long petId) {
        System.out.println("updatePetWithForm");
        return pet -> completedFuture(NotUsed.getInstance());
    }

    @Override
    public ServiceCall<NotUsed, ModelApiResponse> uploadFile(Long petId) {
        System.out.println("uploadFile");
        return notUsed -> completedFuture(ModelApiResponse);
    }
}