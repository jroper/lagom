/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.javadsl.api.deser;

import com.lightbend.lagom.internal.javadsl.api.UnresolvedOptionalPathParamSerializer;
import com.lightbend.lagom.internal.javadsl.api.UnresolvedSequencePathParamSerializer;
import org.pcollections.PSequence;
import org.pcollections.TreePVector;

import java.util.*;
import java.util.function.Function;

/**
 * Id Serializers.
 */
public final class PathParamSerializers {

    private PathParamSerializers() {}

    /**
     * Create a PathParamSerializer for required parameters.
     */
    public static <Param> PathParamSerializer<Param> required(String name, Function<String, Param> deserialize,
            Function<Param, String> serialize) {
        return new NamedPathParamSerializer<Param>(name) {
            @Override
            public PSequence<String> serialize(Param parameter) {
                return TreePVector.singleton(serialize.apply(parameter));
            }

            @Override
            public Param deserialize(PSequence<String> parameters) {
                if (parameters.isEmpty()) {
                    throw new IllegalArgumentException(name + " parameter is required");
                } else {
                    return deserialize.apply(parameters.get(0));
                }
            }
        };
    }

    /**
     * Create a PathParamSerializer for optional parameters.
     */
    public static <Param> PathParamSerializer<Optional<Param>> optional(String name, Function<String, Param> deserialize,
            Function<Param, String> serialize) {
        return new NamedPathParamSerializer<Optional<Param>>("Optional(" + name + ")") {
            @Override
            public PSequence<String> serialize(Optional<Param> parameter) {
                return parameter.map(p -> TreePVector.singleton(serialize.apply(p))).orElse(TreePVector.empty());
            }

            @Override
            public Optional<Param> deserialize(PSequence<String> parameters) {
                if (parameters.isEmpty()) {
                    return Optional.empty();
                } else {
                    return Optional.of(deserialize.apply(parameters.get(0)));
                }
            }
        };
    }

    public static <Param> PathParamSerializer<PSequence<Param>> sequence(String name, Function<String, Param> deserialize,
            Function<Param, String> serialize) {
        return new NamedPathParamSerializer<PSequence<Param>>("Sequence(" + name + ")") {
            @Override
            public PSequence<String> serialize(PSequence<Param> parameters) {
                PSequence<String> result = TreePVector.empty();
                for (Param param: parameters) {
                    result = result.plus(serialize.apply(param));
                }
                return result;
            }

            @Override
            public PSequence<Param> deserialize(PSequence<String> parameters) {
                PSequence<Param> result = TreePVector.empty();
                for (String param: parameters) {
                    result = result.plus(deserialize.apply(param));
                }
                return result;
            }
        };
    }

    /**
     * A String path param serializer.
     */
    public static final PathParamSerializer<String> STRING = required("String", Function.identity(),
            Function.identity());

    /**
     * A Long path param serializer.
     */
    public static final PathParamSerializer<Long> LONG = required("Long", Long::parseLong, l -> l.toString());

    /**
     * An Integer path param serializer.
     */
    public static final PathParamSerializer<Integer> INTEGER = required("Integer", Integer::parseInt, i -> i.toString());

    /**
     * A Boolean path param serializer.
     */
    public static final PathParamSerializer<Boolean> BOOLEAN = required("Boolean", Boolean::parseBoolean,
            b -> b.toString());

    /**
     * A UUID path param serializer.
     */
    public static final PathParamSerializer<UUID> UUID = required("UUID", java.util.UUID::fromString,
            u -> u.toString());

    /**
     * A generic (unresolved) optional serializer.
     */
    public static final PathParamSerializer<Optional<Object>> OPTIONAL = new UnresolvedOptionalPathParamSerializer<>();

    /**
     * A generic (unresolved) psequence serializer.
     */
    public static final PathParamSerializer<PSequence<Object>> SEQUENCE = new UnresolvedSequencePathParamSerializer<>();

    private static abstract class NamedPathParamSerializer<Param> implements PathParamSerializer<Param> {
        private final String name;

        public NamedPathParamSerializer(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        @Override
        public String toString() {
            return "PathParamSerializer(" + name + ")";
        }
    }
}
