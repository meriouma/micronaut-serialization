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
package io.micronaut.serde.processor.jackson;

import java.lang.annotation.Annotation;
import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonMerge;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.annotation.JsonTypeId;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.micronaut.context.annotation.DefaultImplementation;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.beans.visitor.IntrospectedTypeElementVisitor;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.serde.annotation.SerdeConfig;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A visitor that provides validation and extended handling for JSON annotations.
 */
public class SerdeAnnotationVisitor implements TypeElementVisitor<SerdeConfig, SerdeConfig> {

    private MethodElement anyGetterMethod;
    private MethodElement anySetterMethod;
    private FieldElement anyGetterField;
    private FieldElement anySetterField;

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return CollectionUtils.setOf(
                "com.fasterxml.jackson.annotation.*",
                "jakarta.json.bind.annotation.*",
                "io.micronaut.serde.annotation.*",
                "org.bson.codecs.pojo.annotations.*"
        );
    }

    private Set<Class<? extends Annotation>> getUnsupportedJacksonAnnotations() {
        return CollectionUtils.setOf(
                JsonFilter.class,
                JsonBackReference.class,
                JsonAutoDetect.class,
                JsonMerge.class
        );
    }

    @Override
    public void visitField(FieldElement element, VisitorContext context) {
        if (checkForErrors(element, context)) {
            return;
        }
        if (element.hasDeclaredAnnotation(SerdeConfig.AnyGetter.class)) {
            if (element.hasDeclaredAnnotation(SerdeConfig.Unwrapped.class)) {
                context.fail("A field annotated with AnyGetter cannot be unwrapped", element);
            } else if (!element.getGenericField().isAssignable(Map.class)) {
                context.fail("A field annotated with AnyGetter must be a Map", element);
            } else {
                if (anyGetterField != null) {
                    context.fail("Only a single AnyGetter field is supported, another defined: " + anyGetterField.getDescription(true), element);
                } else if (anyGetterMethod != null) {
                    context.fail("Cannot define both an AnyGetter field and an AnyGetter method: " + anyGetterMethod.getDescription(true), element);
                } else {
                    this.anyGetterField = element;
                }
            }
        } else if (element.hasDeclaredAnnotation(SerdeConfig.AnySetter.class)) {
            if (element.hasDeclaredAnnotation(SerdeConfig.Unwrapped.class)) {
                context.fail("A field annotated with AnySetter cannot be unwrapped", element);
            } else if (!element.getGenericField().isAssignable(Map.class)) {
                context.fail("A field annotated with AnySetter must be a Map", element);
            } else {
                if (anySetterField != null) {
                    context.fail("Only a single AnySetter field is supported, another defined: " + anySetterField.getDescription(true), element);
                } else if (anySetterMethod != null) {
                    context.fail("Cannot define both an AnySetter field and an AnySetter method: " + anySetterMethod.getDescription(true), element);
                } else {
                    this.anySetterField = element;
                }
            }
        }
    }

    @Override
    public void visitConstructor(ConstructorElement element, VisitorContext context) {
        if (checkForErrors(element, context)) {
            return;
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (checkForErrors(element, context)) {
            return;
        }
        if (element.hasDeclaredAnnotation(SerdeConfig.Getter.class)) {
            if (element.isStatic()) {
                context.fail("A method annotated with JsonGetter cannot be static", element);
            } else if (element.getReturnType().getName().equals("void")) {
                context.fail("A method annotated with JsonGetter cannot return void", element);
            } else if (element.hasParameters()) {
                context.fail("A method annotated with JsonGetter cannot define arguments", element);
            }
        } else if (element.hasDeclaredAnnotation(SerdeConfig.Setter.class)) {
            if (element.isStatic()) {
                context.fail("A method annotated with JsonSetter cannot be static", element);
            } else {
                final ParameterElement[] parameters = element.getParameters();
                if (parameters.length != 1) {
                    context.fail("A method annotated with JsonSetter must specify exactly 1 argument", element);
                }
            }
        } else if (element.hasDeclaredAnnotation(SerdeConfig.AnyGetter.class)) {
            if (this.anyGetterMethod == null) {
                this.anyGetterMethod = element;
            } else {
                context.fail("Type already defines a method annotated with JsonAnyGetter: " + anyGetterMethod.getDescription(true), element);
            }

            if (element.hasDeclaredAnnotation(SerdeConfig.Unwrapped.class)) {
                context.fail("A method annotated with AnyGetter cannot be unwrapped", element);
            } else if (element.isStatic()) {
                context.fail("A method annotated with AnyGetter cannot be static", element);
            } else if (!element.getGenericReturnType().isAssignable(Map.class)) {
                context.fail("A method annotated with AnyGetter must return a Map", element);
            } else if (element.hasParameters()) {
                context.fail("A method annotated with AnyGetter cannot define arguments", element);
            }
        } else if (element.hasDeclaredAnnotation(SerdeConfig.AnySetter.class)) {
            if (this.anySetterMethod == null) {
                this.anySetterMethod = element;
            } else {
                context.fail("Type already defines a method annotated with JsonAnySetter: " + anySetterMethod.getDescription(true), element);
            }
            if (element.hasDeclaredAnnotation(SerdeConfig.Unwrapped.class)) {
                context.fail("A method annotated with AnyGetter cannot be unwrapped", element);
            } else if (element.isStatic()) {
                context.fail("A method annotated with AnySetter cannot be static", element);
            } else {
                final ParameterElement[] parameters = element.getParameters();
                if (parameters.length == 1) {
                   if (!parameters[0].getGenericType().isAssignable(Map.class)) {
                       context.fail("A method annotated with AnySetter must either define a single parameter of type Map or define exactly 2 parameters, the first of which should be of type String", element);
                   }
                } else if (parameters.length != 2 || !parameters[0].getGenericType().isAssignable(String.class)) {
                    context.fail("A method annotated with AnySetter must either define a single parameter of type Map or define exactly 2 parameters, the first of which should be of type String", element);
                }
            }
        }
    }

    private boolean checkForErrors(Element element, VisitorContext context) {
        for (Class<? extends Annotation> annotation : getUnsupportedJacksonAnnotations()) {
            if (element.hasDeclaredAnnotation(annotation)) {
                context.fail("Annotation @" + annotation.getSimpleName() + " is not supported", element);
            }
        }
        final String error = element.stringValue(SerdeConfig.Error.class).orElse(null);
        if (error != null) {
            context.fail(error, element);
            return true;
        }

        final String pattern = element.stringValue(SerdeConfig.class, SerdeConfig.PATTERN).orElse(null);
        if (pattern != null) {
            ClassElement type = resolvePropertyType(element);

            if (isNumberType(type)) {
                try {
                    new DecimalFormat(pattern);
                } catch (Exception e) {
                    context.fail("Specified pattern [" + pattern + "] is not a valid decimal format. See the javadoc for DecimalFormat: " + e.getMessage(), element);
                }
            } else if (type.isAssignable(Temporal.class)) {
                try {
                    DateTimeFormatter.ofPattern(pattern);
                } catch (Exception e) {
                    context.fail("Specified pattern [" + pattern + "] is not a valid date format. See the javadoc for DateTimeFormatter: " + e.getMessage(), element);
                }
            }
        }
        return false;
    }

    private boolean isNumberType(ClassElement type) {
        if (type == null) {
            return false;
        }
        return type.isAssignable(Number.class) ||
                (type.isPrimitive() && ClassUtils.getPrimitiveType(type.getName())
                        .map(ReflectionUtils::getWrapperType)
                        .map(Number.class::isAssignableFrom).orElse(false));
    }

    private ClassElement resolvePropertyType(Element element) {
        ClassElement type = null;
        if (element instanceof FieldElement) {
            type = ((FieldElement) element).getGenericField().getType();
        } else if (element instanceof MethodElement) {
            MethodElement methodElement = (MethodElement) element;
            if (!methodElement.hasParameters()) {
                type = methodElement.getGenericReturnType();
            } else {
                type = methodElement.getParameters()[0].getGenericType();
            }
        }
        return type;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        this.anyGetterMethod = null; // reset
        this.anySetterMethod = null; // reset
        this.anyGetterField = null; // reset
        this.anySetterField = null; // reset
        if (checkForErrors(element, context)) {
            return;
        }
        if (isJsonAnnotated(element)) {
            if (!element.hasStereotype(Serdeable.Serializable.class) &&
                    !element.hasStereotype(Serdeable.Deserializable.class)) {
                element.annotate(Serdeable.class);
                element.annotate(Introspected.class, (builder) -> {
                    builder.member("accessKind", Introspected.AccessKind.METHOD, Introspected.AccessKind.FIELD);
                    builder.member("visibility", "PUBLIC");
                });
            }

            final String[] ignoresProperties = element.stringValues(JsonIgnoreProperties.class);
            if (ArrayUtils.isNotEmpty(ignoresProperties)) {
                final boolean allowGetters = element.booleanValue(JsonIgnoreProperties.class, "allowGetters").orElse(false);
                final boolean allowSetters = element.booleanValue(JsonIgnoreProperties.class, "allowSetters").orElse(false);
                final Set<String> ignoredSet = CollectionUtils.setOf(ignoresProperties);
                final List<PropertyElement> beanProperties = element.getBeanProperties();
                for (PropertyElement beanProperty : beanProperties) {
                    if (ignoredSet.contains(beanProperty.getName())) {
                        final Consumer<Element> configurer = m ->
                                m.annotate(SerdeConfig.class, (builder) ->
                                        builder.member(SerdeConfig.IGNORED, true)
                                );
                        if (allowGetters) {
                            beanProperty.getWriteMethod().ifPresent(configurer);
                        } else if (allowSetters) {
                            beanProperty.getReadMethod().ifPresent(configurer);
                        } else {
                            configurer.accept(beanProperty);
                        }
                    }
                }
            }

            final Optional<ClassElement> superType = findTypeInfo(element);
            if (superType.isPresent()) {
                final ClassElement typeInfo = superType.get();
                final SerdeConfig.Subtyped.DiscriminatorValueKind discriminatorValueKind = getDiscriminatorValueKind(typeInfo);
                element.annotate(SerdeConfig.class, builder -> {
                    final String typeName = element.stringValue(JsonTypeName.class).orElseGet(() ->
                          discriminatorValueKind == SerdeConfig.Subtyped.DiscriminatorValueKind.CLASS ? element.getName() : element.getSimpleName()
                    );
                    String typeProperty = resolveTypeProperty(superType).orElseGet(() ->
                       discriminatorValueKind == SerdeConfig.Subtyped.DiscriminatorValueKind.CLASS ? "@class" : "@type"
                    );
                    final JsonTypeInfo.As include = resolveInclude(superType).orElse(null);
                    if (include == JsonTypeInfo.As.WRAPPER_OBJECT) {
                        builder.member(SerdeConfig.TYPE_NAME, typeName);
                        builder.member(SerdeConfig.WRAPPER_PROPERTY, typeName);
                    } else {
                        builder.member(SerdeConfig.TYPE_NAME, typeName);
                        builder.member(SerdeConfig.TYPE_PROPERTY, typeProperty);
                    }
                });
            }

            element.findAnnotation(JsonTypeInfo.class).ifPresent((typeInfo) -> {
                final JsonTypeInfo.Id use = typeInfo.enumValue("use", JsonTypeInfo.Id.class).orElse(null);
                final JsonTypeInfo.As include = typeInfo.enumValue("include", JsonTypeInfo.As.class)
                        .orElse(JsonTypeInfo.As.WRAPPER_OBJECT);
                typeInfo.stringValue("defaultImpl").ifPresent(di ->
                      element.annotate(DefaultImplementation.class, (builder) ->
                              builder.member(
                                      AnnotationMetadata.VALUE_MEMBER,
                                      new AnnotationClassValue<>(di)
                              ))
                );

                switch (include) {
                case PROPERTY:
                case WRAPPER_OBJECT:
                    element.annotate(SerdeConfig.Subtyped.class, (builder) -> {
                        builder.member(SerdeConfig.Subtyped.DISCRIMINATOR_TYPE, include.name());
                    });
                    break;
                default:
                    context.fail("Only 'include' of type PROPERTY or WRAPPER_OBJECT are supported", element);
                }
                if (use == null) {
                    context.fail("You must specify 'use' member when using @JsonTypeInfo", element);
                } else {
                    switch (use) {
                    case CLASS:
                    case NAME:
                        element.annotate(SerdeConfig.Subtyped.class, (builder) -> {
                            builder.member(SerdeConfig.Subtyped.DISCRIMINATOR_VALUE, use.name());
                            final String property = typeInfo.stringValue("property")
                                    .orElseGet(() ->
                                                       use == JsonTypeInfo.Id.CLASS ? "@class" : "@type"
                                    );
                            builder.member(SerdeConfig.Subtyped.DISCRIMINATOR_PROP, property);
                        });
                        break;
                    default:
                        context.fail("Only 'use' of type CLASS or NAME are supported", element);
                    }
                }
            });
        }
    }

    private SerdeConfig.Subtyped.DiscriminatorValueKind getDiscriminatorValueKind(ClassElement typeInfo) {
        return typeInfo.enumValue(
                SerdeConfig.Subtyped.class,
                SerdeConfig.Subtyped.DISCRIMINATOR_VALUE,
                SerdeConfig.Subtyped.DiscriminatorValueKind.class)
                .orElse(SerdeConfig.Subtyped.DiscriminatorValueKind.CLASS);
    }

    private Optional<ClassElement> findTypeInfo(ClassElement element) {
        // TODO: support interfaces
        final ClassElement superElement = element.getSuperType().orElse(null);
        if (superElement == null) {
            return Optional.empty();
        }
        if (superElement.hasDeclaredAnnotation(JsonTypeInfo.class)) {
            return Optional.of(superElement);
        } else {
            return findTypeInfo(superElement);
        }
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private Optional<String> resolveTypeProperty(Optional<ClassElement> superType) {
        return superType.flatMap(st -> {
            final String property = st.stringValue(JsonTypeInfo.class, "property")
                    .orElse(null);
            if (property != null) {
                return Optional.of(property);
            } else {
                return resolveTypeProperty(st.getSuperType());
            }
        });
    }

    private Optional<JsonTypeInfo.As> resolveInclude(Optional<ClassElement> superType) {
        return superType.flatMap(st -> {
            final JsonTypeInfo.As asValue = st.enumValue(JsonTypeInfo.class, "include", JsonTypeInfo.As.class)
                    .orElse(null);
            if (asValue != null) {
                return Optional.of(asValue);
            } else {
                return resolveInclude(st.getSuperType());
            }
        });
    }

    @Override
    public int getOrder() {
        return IntrospectedTypeElementVisitor.POSITION + 100;
    }

    private boolean isJsonAnnotated(ClassElement element) {
        return Stream.of(
                        JsonClassDescription.class,
                        JsonTypeInfo.class,
                        JsonRootName.class,
                        JsonTypeName.class,
                        JsonTypeId.class,
                        JsonAutoDetect.class)
                .anyMatch(element::hasDeclaredAnnotation) ||
                (element.hasStereotype(Serdeable.Serializable.class) || element.hasStereotype(Serdeable.Deserializable.class));
    }

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }
}
