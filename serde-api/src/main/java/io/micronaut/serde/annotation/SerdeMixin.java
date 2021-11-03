/*
 * Copyright 2017-2021 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.serde.annotation;

import io.micronaut.context.annotation.AliasFor;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;

import java.lang.annotation.*;

/**
 * Annotation to trigger serializer generation for another class.
 */
@Internal
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Repeatable(SerdeMixin.Repeated.class)
public @interface SerdeMixin {
    @AliasFor(annotation = Introspected.class, member = "classes")
    Class<?> value();

    Serdeable config() default @Serdeable;

    /**
     * Repeated wrapper for this annotation.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.SOURCE)
    @interface Repeated {
        SerdeMixin[] value();
    }
}
