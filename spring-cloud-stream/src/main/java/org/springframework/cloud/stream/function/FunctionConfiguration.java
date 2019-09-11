/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.function;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.context.PollableSupplier;
import org.springframework.cloud.function.context.catalog.BeanFactoryAwareFunctionRegistry.FunctionInvocationWrapper;
import org.springframework.cloud.function.context.catalog.FunctionInspector;
import org.springframework.cloud.function.context.catalog.FunctionTypeUtils;
import org.springframework.cloud.function.context.config.FunctionContextUtils;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.binder.BindingCreatedEvent;
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binding.BindableProxyFactory;
import org.springframework.cloud.stream.config.BinderFactoryAutoConfiguration;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.messaging.DirectWithAttributesChannel;
import org.springframework.cloud.stream.messaging.Sink;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.MethodMetadata;
import org.springframework.integration.channel.MessageChannelReactiveUtils;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlowBuilder;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.handler.ServiceActivatingHandler;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

/**
 * @author Oleg Zhurakousky
 * @author David Turanski
 * @author Ilayaperumal Gopinathan
 * @since 2.1
 */
@Configuration
@EnableConfigurationProperties(StreamFunctionProperties.class)
@Import(BinderFactoryAutoConfiguration.class)
class FunctionConfiguration {

	@Bean
	public InitializingBean functionChannelBindingInitializer(FunctionCatalog functionCatalog, FunctionInspector functionInspector,
			StreamFunctionProperties functionProperties, @Nullable BindableProxyFactory[] bindableProxyFactory, BindingServiceProperties serviceProperties) {
		return new FunctionChannelBindingInitializer(functionCatalog, functionInspector, functionProperties,
					ObjectUtils.isEmpty(bindableProxyFactory) ? null : bindableProxyFactory[0], serviceProperties);
	}

	@Bean
	public IntegrationFlow standAloneSupplierFlow(FunctionCatalog functionCatalog, FunctionInspector functionInspector,
			StreamFunctionProperties functionProperties, GenericApplicationContext context) {
		FunctionInvocationWrapper functionWrapper = functionCatalog.lookup(functionProperties.getDefinition());
		IntegrationFlow integrationFlow = null;
		if (ObjectUtils.isEmpty(context.getBeanNamesForAnnotation(EnableBinding.class)) && functionWrapper != null && functionWrapper.isSupplier()) {
			AtomicReference<MonoSink<Object>> triggerRef = new AtomicReference<>();
			Publisher<Object> beginPublishingTrigger = Mono.create(emmiter -> {
				triggerRef.set(emmiter);
			});
			context.addApplicationListener(event -> {
				if (event instanceof BindingCreatedEvent) {
					if (triggerRef.get() != null) {
						triggerRef.get().success();
					}
				}
			});

			RootBeanDefinition bd = (RootBeanDefinition) context.getBeanDefinition(functionProperties.getParsedDefinition()[0]);
			Method factoryMethod = bd.getResolvedFactoryMethod();
			if (factoryMethod == null) {
				Object source = bd.getSource();
				if (source instanceof MethodMetadata) {
					Class<?> factory = ClassUtils.resolveClassName(((MethodMetadata) source).getDeclaringClassName(), null);
					Class<?>[] params = FunctionContextUtils.getParamTypesFromBeanDefinitionFactory(factory, bd);
					factoryMethod = ReflectionUtils.findMethod(factory, ((MethodMetadata) source).getMethodName(), params);
				}
			}
			Assert.notNull(factoryMethod, "Failed to introspect factory method since it was not discovered for function '"
							+ functionProperties.getDefinition() + "'");
			PollableSupplier pollable = factoryMethod.getReturnType().isAssignableFrom(Supplier.class)
					? AnnotationUtils.findAnnotation(factoryMethod, PollableSupplier.class)
							: null;

			if (!functionProperties.isComposeFrom() && !functionProperties.isComposeTo()) {
				integrationFlow = this.integrationFlowFromProvidedSupplier(functionWrapper, functionInspector, beginPublishingTrigger, pollable)
						.channel("output").get();
			}
		}

		return integrationFlow;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private IntegrationFlowBuilder integrationFlowFromProvidedSupplier(Supplier<?> supplier,
			FunctionInspector inspector, Publisher<Object> beginPublishingTrigger, PollableSupplier pollable) {

		IntegrationFlowBuilder integrationFlowBuilder;
		Type functionType = FunctionTypeUtils.getFunctionType(supplier, inspector);


		boolean splittable = pollable != null && (boolean) AnnotationUtils.getAnnotationAttributes(pollable).get("splittable");

		if (pollable == null && FunctionTypeUtils.isReactive(FunctionTypeUtils.getInputType(functionType, 0))) {
			Publisher publisher = (Publisher) supplier.get();
			publisher = publisher instanceof Mono
					? ((Mono) publisher).delaySubscription(beginPublishingTrigger).map(this::wrapToMessageIfNecessary)
							: ((Flux) publisher).delaySubscription(beginPublishingTrigger).map(this::wrapToMessageIfNecessary);

			integrationFlowBuilder  = IntegrationFlows.from(publisher);
		}
		else { // implies pollable
			integrationFlowBuilder = IntegrationFlows.from(supplier);
			if (splittable) {
				integrationFlowBuilder = integrationFlowBuilder.split();
			}
		}

		return integrationFlowBuilder;
	}

	@SuppressWarnings("unchecked")
	private <T> Message<T> wrapToMessageIfNecessary(T value) {
		return value instanceof Message ? (Message<T>) value : MessageBuilder.withPayload(value).setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON).build();
	}

	/**
	 *
	 * @author Oleg Zhurakousky
	 * @since 3.0
	 */
	private static class FunctionChannelBindingInitializer implements InitializingBean, ApplicationContextAware {

		private static Log logger = LogFactory.getLog(FunctionChannelBindingInitializer.class);

		private final FunctionCatalog functionCatalog;

		private final FunctionInspector functionInspector;

		private final StreamFunctionProperties functionProperties;

		private final BindableProxyFactory bindableProxyFactory;

		private final BindingServiceProperties serviceProperties;

		private GenericApplicationContext context;


		FunctionChannelBindingInitializer(FunctionCatalog functionCatalog, FunctionInspector functionInspector,
				StreamFunctionProperties functionProperties, BindableProxyFactory bindableProxyFactory, BindingServiceProperties serviceProperties) {
			this.functionCatalog = functionCatalog;
			this.functionInspector = functionInspector;
			this.functionProperties = functionProperties;
			this.bindableProxyFactory = bindableProxyFactory;
			this.serviceProperties = serviceProperties;
		}

		@Override
		public void afterPropertiesSet() throws Exception {
			MessageChannel messageChannel = null;
			String channelName = Sink.INPUT;
			if (context.containsBean(channelName)) {
				Object bean = context.getBean(channelName);
				if (bean instanceof MessageChannel) {
					messageChannel = context.getBean(channelName, MessageChannel.class);
				}
			}
			if (messageChannel == null && context.containsBean(Source.OUTPUT)) {
				channelName = "output";
				Object bean = context.getBean(channelName);
				if (bean instanceof MessageChannel) {
					messageChannel = context.getBean(channelName, SubscribableChannel.class);
				}
			}

			if (messageChannel != null && functionCatalog.lookup(functionProperties.getDefinition()) != null) {
				this.doPostProcess(channelName, (SubscribableChannel) messageChannel);
			}
		}

		@Override
		public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
			this.context = (GenericApplicationContext) applicationContext;
		}

		private void doPostProcess(String channelName, SubscribableChannel messageChannel) {
			//TODO there is something about moving channel interceptors in AMCB (not sure if it is still required)
			if (functionProperties.isComposeTo() && messageChannel instanceof SubscribableChannel && Sink.INPUT.equals(channelName)) {
				throw new UnsupportedOperationException("Composing at tail is not currently supported");
			}
			else if (functionProperties.isComposeFrom() && Source.OUTPUT.equals(channelName)) {
				Assert.notNull(this.bindableProxyFactory, "Can not compose function into the existing app since `bindableProxyFactory` is null.");
				logger.info("Composing at the head of 'output' channel");
				BindingProperties properties = this.serviceProperties.getBindings().get(Source.OUTPUT);
				FunctionInvocationWrapper function = functionCatalog.lookup(functionProperties.getDefinition(), properties.getContentType());
				ServiceActivatingHandler handler = new ServiceActivatingHandler(new FunctionWrapper(function));
				handler.setBeanFactory(context);
				handler.afterPropertiesSet();

				DirectWithAttributesChannel newOutputChannel = new DirectWithAttributesChannel();
				newOutputChannel.setAttribute("type", "output");
				newOutputChannel.setComponentName("output.extended");
				this.context.registerBean("output.extended", MessageChannel.class, () -> newOutputChannel);
				this.bindableProxyFactory.replaceOutputChannel(channelName, "output.extended", newOutputChannel);

				handler.setOutputChannelName("output.extended");
				SubscribableChannel subscribeChannel = (SubscribableChannel) messageChannel;
				subscribeChannel.subscribe(handler);
			}
			else {
				if (Sink.INPUT.equals(channelName)) {
					BindingProperties properties = this.serviceProperties.getBindings().get(Sink.INPUT);
					FunctionInvocationWrapper function = functionCatalog.lookup(functionProperties.getDefinition(), properties.getContentType());
					this.postProcessForStandAloneFunction(function, messageChannel);
				}
			}
		}

		private void postProcessForStandAloneFunction(FunctionInvocationWrapper function, MessageChannel inputChannel) {
			Type functionType = FunctionTypeUtils.getFunctionType(function, this.functionInspector);
			if (FunctionTypeUtils.isReactive(FunctionTypeUtils.getInputType(functionType, 0))) {
				MessageChannel outputChannel = context.getBean(Source.OUTPUT, MessageChannel.class);
				SubscribableChannel subscribeChannel = (SubscribableChannel) inputChannel;
				Publisher<?> publisher = this.enhancePublisher(MessageChannelReactiveUtils.toPublisher(subscribeChannel));
				this.subscribeToInput(function, publisher, outputChannel::send);
			}
			else {
				ServiceActivatingHandler handler = new ServiceActivatingHandler(new FunctionWrapper(function));
				handler.setBeanFactory(context);
				handler.afterPropertiesSet();
				if (!FunctionTypeUtils.isConsumer(functionType)) {
					handler.setOutputChannelName(Source.OUTPUT);
				}
				SubscribableChannel subscribeChannel = (SubscribableChannel) inputChannel;
				subscribeChannel.subscribe(handler);
			}
		}

		/*
		 * Enhance publisher to add error handling, retries etc.
		 */
		@SuppressWarnings({ "unchecked", "rawtypes" })
		private Publisher enhancePublisher(Publisher publisher) {
			Flux flux = Flux.from(publisher)
					.concatMap(message -> {
						ConsumerProperties consumerProperties = this.serviceProperties.getBindings().get(Sink.INPUT).getConsumer();
						return Flux.just(message)
								.doOnError(e -> {
									e.printStackTrace();
								})
								.retryBackoff(
										consumerProperties.getMaxAttempts(),
										Duration.ofMillis(consumerProperties.getBackOffInitialInterval()),
										Duration.ofMillis(consumerProperties.getBackOffMaxInterval())
								)
								.onErrorResume(e -> {
									e.printStackTrace();
									return Mono.empty();
								});

					});
			return flux;
		}


		@SuppressWarnings({ "unchecked", "rawtypes" })
		private <I, O> void subscribeToInput(Function function,
				Publisher<?> publisher, Consumer<Message<O>> outputProcessor) {

			Function<Flux<Message<I>>, Flux<Message<O>>> functionInvoker = function;
			Flux<?> inputPublisher = Flux.from(publisher);
			subscribeToOutput(outputProcessor,
					functionInvoker.apply((Flux<Message<I>>) inputPublisher)).subscribe();
		}

		private <O> Mono<Void> subscribeToOutput(Consumer<Message<O>> outputProcessor,
				Publisher<Message<O>> outputPublisher) {

			Flux<Message<O>> output = outputProcessor == null ? Flux.from(outputPublisher)
					: Flux.from(outputPublisher).doOnNext(outputProcessor);
			return output.then();
		}
	}
	/**
	 *
	 * Ensure that SI does not attempt any conversion and sends a raw Message.
	 *
	 */
	@SuppressWarnings("rawtypes")
	private static class FunctionWrapper implements Function<Message<byte[]>, Object> {
		private final Function function;

		FunctionWrapper(Function function) {
			this.function = function;
		}
		@SuppressWarnings("unchecked")
		@Override
		public Message<byte[]> apply(Message<byte[]> t) {
			Object result = function.apply(t);
			if (result instanceof Publisher) {
				throw new IllegalStateException("Routing to functions that return Publisher is not supported in the context of Spring Cloud Stream.");
			}
			return (Message<byte[]>) result;
		}
	}
}
