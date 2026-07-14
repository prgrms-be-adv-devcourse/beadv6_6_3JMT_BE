package com.prompthub.order.infra.grpc.client.payment;

import com.prompthub.grpc.payment.refund.v1.PaymentRefundQueryServiceGrpc;
import com.prompthub.order.application.client.PaymentRefundStatusClient;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.grpc.client.GrpcChannelFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentRefundGrpcClientConfigTest {

	private ManagedChannel channel;

	@AfterEach
	void tearDown() {
		if (channel != null) {
			channel.shutdownNow();
		}
	}

	@Test
	void createsNamedPaymentRefundStubAndProdAdapterWithoutConnecting() {
		channel = InProcessChannelBuilder.forName("unused-payment-refund-config-test").directExecutor().build();
		GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
		when(channelFactory.createChannel(PaymentRefundGrpcClientConfig.CHANNEL_NAME)).thenReturn(channel);

		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("prod");
			context.getBeanFactory().registerSingleton("grpcChannelFactory", channelFactory);
			context.getBeanFactory().registerSingleton("paymentRefundGrpcResilience", TestResilienceFactory.create(1));
			context.register(PaymentRefundGrpcClientConfig.class, PaymentRefundGrpcClientAdapter.class);
			context.refresh();

			PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceBlockingStub stub =
				context.getBean(PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceBlockingStub.class);
			PaymentRefundStatusClient client = context.getBean(PaymentRefundStatusClient.class);

			verify(channelFactory).createChannel("payment-refund");
			assertThat(stub.getChannel()).isSameAs(channel);
			assertThat(client).isInstanceOf(PaymentRefundGrpcClientAdapter.class);
		}
	}

}
