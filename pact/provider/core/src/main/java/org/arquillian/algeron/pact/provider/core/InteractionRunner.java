package org.arquillian.algeron.pact.provider.core;

import au.com.dius.pact.model.Consumer;
import au.com.dius.pact.model.Pact;
import au.com.dius.pact.model.ProviderState;
import au.com.dius.pact.model.RequestResponseInteraction;
import au.com.dius.pact.model.RequestResponsePact;
import org.apache.http.HttpRequest;
import org.arquillian.algeron.pact.provider.core.httptarget.Target;
import org.arquillian.algeron.pact.provider.api.Pacts;
import org.arquillian.algeron.pact.provider.spi.ArquillianTestClassAwareTarget;
import org.arquillian.algeron.pact.provider.spi.CurrentConsumer;
import org.arquillian.algeron.pact.provider.spi.CurrentInteraction;
import org.arquillian.algeron.pact.provider.spi.PactProviderExecutionAwareTarget;
import org.arquillian.algeron.pact.provider.spi.State;
import org.arquillian.algeron.pact.provider.spi.TargetRequestFilter;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.core.spi.EventContext;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.arquillian.test.spi.event.suite.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Runner that will execute the same test for all defined Pacts
 */
public class InteractionRunner {

    private Logger logger = Logger.getLogger(InteractionRunner.class.getName());

    @Inject
    Instance<Pacts> pactsInstance;

    @Inject
    Instance<Target> targetInstance;


    public void executePacts(@Observes EventContext<Test> test) {
        TestClass testClass = test.getEvent().getTestClass();

        final List<Throwable> errors = new ArrayList<>();
        validateState(testClass, errors);
        validateTargetRequestFilters(testClass, errors);
        validateTestTarget(testClass, errors);

        Field interactionField = validateAndGetResourceField(testClass, RequestResponseInteraction.class, CurrentInteraction.class, errors);
        Field consumerField = validateAndGetResourceField(testClass, Consumer.class, CurrentConsumer.class, errors);

        if (errors.size() != 0) {
            String errorMessage = errors.stream()
                    .map(error -> error.getMessage())
                    .collect(Collectors.joining(" * "));
            throw new IllegalArgumentException(errorMessage);
        }

        Pacts pacts = pactsInstance.get();
        if (pacts != null) {
            executePacts(test, pacts, interactionField, consumerField);
        } else {
            logger.log(Level.WARNING, "No pacts read for execution");
        }

    }

    private void executePacts(EventContext<Test> test, final Pacts pacts, final Field interactionField, final Field consumerField) {
        final TestClass testClass = test.getEvent().getTestClass();
        final Object testInstance = test.getEvent().getTestInstance();

        for (Pact pact : pacts.getPacts()) {
            RequestResponsePact requestResponsePact = (RequestResponsePact) pact;

            // Inject current consumer
            if (consumerField != null) {
                setField(testInstance, consumerField, pact.getConsumer());
            }

            for (final RequestResponseInteraction interaction : requestResponsePact.getInteractions()) {
                executeStateChanges(interaction, testClass, testInstance);

                Target target = targetInstance.get();

                if (target instanceof ArquillianTestClassAwareTarget) {
                    ArquillianTestClassAwareTarget arquillianTestClassAwareTarget = (ArquillianTestClassAwareTarget) target;
                    arquillianTestClassAwareTarget.setTestClass(testClass, testInstance);
                }

                if (target instanceof PactProviderExecutionAwareTarget) {
                    PactProviderExecutionAwareTarget pactProviderExecutionAwareTarget = (PactProviderExecutionAwareTarget) target;
                    pactProviderExecutionAwareTarget.setConsumer(pact.getConsumer());
                    pactProviderExecutionAwareTarget.setRequestResponseInteraction(interaction);
                }

                // Inject current interaction to test
                if (interactionField != null) {
                    setField(testInstance, interactionField, interaction);
                }

                // run the test
                test.proceed();
            }

        }
    }

    private void setField(Object testInstance, Field fieldTarget, Object pact) {
        try {
            fieldTarget.setAccessible(true);
            fieldTarget.set(testInstance, pact);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(e);
        }
    }

    protected void validateState(final TestClass testClass, final List<Throwable> errors) {
        Arrays.stream(testClass.getMethods(State.class)).forEach(method -> {
            validatePublicVoidMethods(method, errors);
        });
    }

    protected void validateTargetRequestFilters(final TestClass testClass, final List<Throwable> errors) {
        Method[] methods = testClass.getMethods(TargetRequestFilter.class);
        for (Method method : methods) {
            if (!isPublic(method)) {
                String publicError = String.format("Method %s annotated with %s should be public.", method.getName(), TargetRequestFilter.class.getName());
                logger.log(Level.SEVERE, publicError);
                errors.add(new IllegalArgumentException(publicError));
            }

            if (method.getParameterCount() != 1) {
                String argumentError = String.format("Method %s should take only a single %s parameter", method.getName(), HttpRequest.class.getName());
                logger.log(Level.SEVERE, argumentError);
                errors.add(new IllegalArgumentException(argumentError));
            } else if (!HttpRequest.class.isAssignableFrom(method.getParameterTypes()[0])) {
                String httpRequestError = String.format("Method %s should take only %s parameter", method.getName(), HttpRequest.class.getName());
                logger.log(Level.SEVERE, httpRequestError);
                errors.add(new IllegalArgumentException(httpRequestError));
            }
        }
    }

    protected void validateTestTarget(TestClass testClass, final List<Throwable> errors) {
        final List<Field> fieldsWithAnnotation = getFieldsWithAnnotation(testClass.getJavaClass(), ArquillianResource.class)
                .stream()
                .filter(f -> Target.class.isAssignableFrom(f.getType()))
                .collect(Collectors.toList());
        if (fieldsWithAnnotation.size() > 1) {
            final String testTargetError = String.format("Test should have one field annotated with %s of type %s", ArquillianResource.class.getName(), Target.class.getName());
            logger.log(Level.SEVERE, testTargetError);
            errors.add(new IllegalArgumentException(testTargetError));
        } else if (fieldsWithAnnotation.size() == 0) {
            final String testTargetError = String.format("Field annotated with %s should implement %s and didn't found any", ArquillianResource.class.getName(), Target.class.getName());
            logger.log(Level.SEVERE, testTargetError);
            errors.add(new IllegalArgumentException(testTargetError));
        }
    }

    protected void validatePublicVoidMethods(Method method, final List<Throwable> errors) {
        if (!isPublic(method)) {
            String publicError = String.format("Method %s should be public.", method.getName());
            logger.log(Level.SEVERE, publicError);
            errors.add(new IllegalArgumentException(publicError));
        }
        if (!returnsVoid(method)) {
            String voidError = String.format("Method %s should return void");
            logger.log(Level.SEVERE, voidError);
            errors.add(new IllegalArgumentException(voidError));
        }
    }

    private Field validateAndGetResourceField(TestClass testClass, Class<?> fieldType, Class<? extends Annotation> annotation, List<Throwable> errors) {
        final List<Field> fieldsWithArquillianResource = getFieldsWithAnnotation(testClass.getJavaClass(), annotation);

        List<Field> rri = fieldsWithArquillianResource
                .stream()
                .filter(
                        field -> fieldType.isAssignableFrom(field.getType())
                ).collect(Collectors.toList());

        if (rri.size() > 1) {
            String rriError = String.format("Only one field annotated with %s of type %s should be present", annotation.getName(), fieldType.getName());
            logger.log(Level.SEVERE, rriError);
            errors.add(new IllegalArgumentException(rriError));
        } else {
            if (rri.size() == 1) {
                return rri.get(0);
            }
        }

        return null;

    }

    protected void executeStateChanges(final RequestResponseInteraction interaction, final TestClass testClass, final Object target) {
        if (!interaction.getProviderStates().isEmpty()) {
            for (final ProviderState state : interaction.getProviderStates()) {
                Arrays.stream(testClass.getMethods(State.class))
                        .filter(method -> ArrayUtils.matches(
                                method.getAnnotation(State.class).value(), state.getName()))
                        .forEach(method -> {
                            if (isStateMethodWithMapParameter(method)) {
                                executeMethod(method, target, state.getParams());
                            } else {
                                if (method.getParameterCount() > 0) {
                                    // Use regular expressions to pass parameters.
                                    executeStateMethodWithRegExp(method, state, target);
                                } else {
                                    executeMethod(method, target);
                                }
                            }
                        });
            }
        }
    }

    private void executeStateMethodWithRegExp(Method stateMethod, ProviderState state, Object target) {
        final Optional<String> matchingStateOptional = ArrayUtils.firstMatch(
                stateMethod.getAnnotation(State.class).value(), state.getName());
        // We are sure that at this point, an state is present
        final String matchingState = matchingStateOptional.get();
        final List<String> arguments = ArgumentPatternMatcher.arguments(
                Pattern.compile(matchingState), state.getName());

        if (arguments.size() != stateMethod.getParameterCount()) {
            throw new IllegalArgumentException(String.format("Consumer state %s matches with provider state %s but provider method contains %s arguments instead of matching %s",
                    state.getName(), matchingState, stateMethod.getParameterCount(), arguments.size()));
        }

        Class<?>[] parameterTypes = stateMethod.getParameterTypes();

        final Object[] instances = new Object[parameterTypes.length];

        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameter = parameterTypes[i];
            if (isSimpleType(parameter)) {
                instances[i] = cast(arguments.get(i), parameter);
            } else {
                if (isCollectionType(parameter)) {
                    instances[i] = cast(arguments.get(i));
                }
            }
        }

        executeMethod(stateMethod, target, instances);
    }


    private Collection<String> cast(String argument) {

        final StringTokenizer elementsSeparator = new StringTokenizer(argument, ",");
        final List<String> listElements = new ArrayList<>();
        while (elementsSeparator.hasMoreTokens()) {
            listElements.add(elementsSeparator.nextToken().trim());
        }

        return listElements;
    }

    private Object cast(String argument, Class<?> parameterType) {

        if (String.class.isAssignableFrom(parameterType)) {
            return argument;
        } else {
            if (int.class.isAssignableFrom(parameterType) || Integer.class.isAssignableFrom(parameterType)) {
                return Integer.parseInt(argument);
            } else {
                if (double.class.isAssignableFrom(parameterType) || Double.class.isAssignableFrom(parameterType)) {
                    return Double.parseDouble(argument);
                } else {
                    if (long.class.isAssignableFrom(parameterType) || Long.class.isAssignableFrom(parameterType)) {
                        return Long.parseLong(argument);
                    } else {
                        if (float.class.isAssignableFrom(parameterType) || Float.class.isAssignableFrom(parameterType)) {
                            return Float.parseFloat(argument);
                        }
                    }
                }
            }
        }
        throw new IllegalArgumentException(String.format("Argument %s is of type %s and it is not a Number nor a String or Collection os Strings"));
    }

    private boolean isCollectionType(Class<?> type) {
        return Collection.class.isAssignableFrom(type);
    }

    private boolean isSimpleType(Class<?> type) {
        return type.isPrimitive() || Number.class.isAssignableFrom(type) || String.class.isAssignableFrom(type);
    }

    private boolean isStateMethodWithMapParameter(Method method) {
        return method.getParameterCount() == 1 &&
                Map.class.isAssignableFrom(method.getParameterTypes()[0]);
    }

    private void executeMethod(Method method, Object target, Object... params) {
        try {
            method.invoke(target, params);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private List<Field> getFieldsWithAnnotation(final Class<?> source,
                                                final Class<? extends Annotation> annotationClass) {
        List<Field> declaredAccessableFields = AccessController
                .doPrivileged(new PrivilegedAction<List<Field>>() {
                    public List<Field> run() {
                        List<Field> foundFields = new ArrayList<Field>();
                        Class<?> nextSource = source;
                        while (nextSource != Object.class) {
                            for (Field field : nextSource.getDeclaredFields()) {
                                if (field.isAnnotationPresent(annotationClass)) {
                                    if (!field.isAccessible()) {
                                        field.setAccessible(true);
                                    }
                                    foundFields.add(field);
                                }
                            }
                            nextSource = nextSource.getSuperclass();
                        }
                        return foundFields;
                    }
                });
        return declaredAccessableFields;
    }

    private boolean isPublic(Method method) {
        return Modifier.isPublic(method.getModifiers());
    }

    private boolean returnsVoid(Method method) {
        return Void.TYPE.equals(method.getReturnType());
    }
}
