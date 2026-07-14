package com.prompthub.order.infra.grpc.client.payment;

import com.prompthub.grpc.payment.refund.v1.PaymentRefundQueryServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration(proxyBeanMethods = false)
@Profile({"dev", "prod"})
public class PaymentRefundGrpcClientConfig {

	static final String CHANNEL_NAME = "payment-refund";

	@Bean
	public PaymentRefundQueryServiceGrpc.PaymentRefundQueryServiceBlockingStub paymentRefundQueryServiceBlockingStub(
		GrpcChannelFactory grpcChannelFactory
	) {
		return PaymentRefundQueryServiceGrpc.newBlockingStub(grpcChannelFactory.createChannel(CHANNEL_NAME));
	}
}
