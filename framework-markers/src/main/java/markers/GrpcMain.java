package markers;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class GrpcMain {
    private static final MethodDescriptor.Marshaller<String> TEXT =
            new MethodDescriptor.Marshaller<>() {
                @Override
                public InputStream stream(String value) {
                    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public String parse(InputStream stream) {
                    try {
                        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };

    private static final MethodDescriptor<String, String> ECHO =
            MethodDescriptor.<String, String>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("markers.Echo/Say")
                    .setRequestMarshaller(TEXT)
                    .setResponseMarshaller(TEXT)
                    .build();

    private static Server server() {
        ServerServiceDefinition service =
                ServerServiceDefinition.builder("markers.Echo")
                        .addMethod(
                                ECHO,
                                ServerCalls.asyncUnaryCall(
                                        (request, observer) -> {
                                            observer.onNext("ok");
                                            observer.onCompleted();
                                        }))
                        .build();
        return ServerBuilder.forPort(0).addService(service).build();
    }

    private static String call(int port) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
        try {
            return ClientCalls.blockingUnaryCall(channel, ECHO, CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS), "hi");
        } finally {
            channel.shutdownNow();
        }
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "client" -> {
                try {
                    call(1);
                } catch (StatusRuntimeException e) {
                    System.out.println("client call failed: " + e.getStatus().getCode());
                }
            }
            case "configured" -> {
                server();
                System.out.println("built");
            }
            case "serving" -> {
                Server server = server().start();
                Support.answered(call(server.getPort()));
                server.shutdownNow();
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
        Support.finish();
    }
}
