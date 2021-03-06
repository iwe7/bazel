// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote.logging;

import static com.google.common.collect.Iterators.advance;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertThrows;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamBlockingStub;
import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.ExecuteDetails;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.FindMissingBlobsDetails;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.GetActionResultDetails;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.RpcCallDetails;
import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.WatchDetails;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.util.io.AsynchronousFileOutputStream;
import com.google.devtools.remoteexecution.v1test.Action;
import com.google.devtools.remoteexecution.v1test.ActionCacheGrpc;
import com.google.devtools.remoteexecution.v1test.ActionCacheGrpc.ActionCacheBlockingStub;
import com.google.devtools.remoteexecution.v1test.ActionCacheGrpc.ActionCacheImplBase;
import com.google.devtools.remoteexecution.v1test.ActionResult;
import com.google.devtools.remoteexecution.v1test.ContentAddressableStorageGrpc;
import com.google.devtools.remoteexecution.v1test.ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub;
import com.google.devtools.remoteexecution.v1test.ContentAddressableStorageGrpc.ContentAddressableStorageImplBase;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.devtools.remoteexecution.v1test.ExecuteRequest;
import com.google.devtools.remoteexecution.v1test.ExecutionGrpc;
import com.google.devtools.remoteexecution.v1test.ExecutionGrpc.ExecutionBlockingStub;
import com.google.devtools.remoteexecution.v1test.ExecutionGrpc.ExecutionImplBase;
import com.google.devtools.remoteexecution.v1test.FindMissingBlobsRequest;
import com.google.devtools.remoteexecution.v1test.FindMissingBlobsResponse;
import com.google.devtools.remoteexecution.v1test.GetActionResultRequest;
import com.google.devtools.remoteexecution.v1test.OutputFile;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.watcher.v1.Change;
import com.google.watcher.v1.ChangeBatch;
import com.google.watcher.v1.Request;
import com.google.watcher.v1.WatcherGrpc;
import com.google.watcher.v1.WatcherGrpc.WatcherImplBase;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.util.MutableHandlerRegistry;
import java.util.Iterator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Tests for {@link com.google.devtools.build.lib.remote.logging.LoggingInterceptor} */
@RunWith(JUnit4.class)
public class LoggingInterceptorTest {
  private final String fakeServerName = "fake server for " + getClass();
  private final MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
  private Server fakeServer;
  private Channel loggedChannel;
  private LoggingInterceptor interceptor;
  private AsynchronousFileOutputStream logStream;

  // This returns a logging interceptor where all calls are handled by the given handler.
  @SuppressWarnings({"rawtypes", "unchecked"})
  private LoggingInterceptor getInterceptorWithAlwaysThisHandler(
      LoggingHandler handler, AsynchronousFileOutputStream outputFile) {
    return new LoggingInterceptor(outputFile) {
      @Override
      public <ReqT, RespT> LoggingHandler<ReqT, RespT> selectHandler(
          MethodDescriptor<ReqT, RespT> method) {
        return handler;
      }
    };
  }

  @Before
  public final void setUp() throws Exception {
    // Use a mutable service registry for later registering the service impl for each test case.
    fakeServer =
        InProcessServerBuilder.forName(fakeServerName)
            .fallbackHandlerRegistry(serviceRegistry)
            .directExecutor()
            .build()
            .start();
    logStream = Mockito.mock(AsynchronousFileOutputStream.class);
    interceptor = new LoggingInterceptor(logStream);
    loggedChannel =
        ClientInterceptors.intercept(
            InProcessChannelBuilder.forName(fakeServerName).directExecutor().build(), interceptor);
  }

  @After
  public void tearDown() throws Exception {
    fakeServer.shutdownNow();
    fakeServer.awaitTermination();
  }

  @Test
  public void testCallOk() {
    ReadRequest request = ReadRequest.newBuilder().setResourceName("test").build();
    ReadResponse response =
        ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("abc")).build();

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
          }
        });

    @SuppressWarnings("unchecked")
    LoggingHandler<ReadRequest, ReadResponse> handler = Mockito.mock(LoggingHandler.class);
    RpcCallDetails details = RpcCallDetails.getDefaultInstance();
    Mockito.when(handler.getDetails()).thenReturn(details);
    AsynchronousFileOutputStream output = Mockito.mock(AsynchronousFileOutputStream.class);

    LoggingInterceptor interceptor = getInterceptorWithAlwaysThisHandler(handler, output);
    Channel channel =
        ClientInterceptors.intercept(
            InProcessChannelBuilder.forName(fakeServerName).directExecutor().build(), interceptor);
    ByteStreamBlockingStub stub = ByteStreamGrpc.newBlockingStub(channel);

    LogEntry expectedEntry =
        LogEntry.newBuilder()
            .setMethodName(ByteStreamGrpc.getReadMethod().getFullMethodName())
            .setDetails(details)
            .setStatus(com.google.rpc.Status.getDefaultInstance())
            .build();

    stub.read(request).next();
    verify(handler).handleReq(request);
    verify(handler).handleResp(response);
    verify(handler).getDetails();
    verify(output).write(expectedEntry);
  }

  @Test
  public void testCallOkMultipleResponses() {
    ReadRequest request = ReadRequest.newBuilder().setResourceName("test").build();
    ReadResponse response1 =
        ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("abc")).build();
    ReadResponse response2 =
        ReadResponse.newBuilder().setData(ByteString.copyFromUtf8("def")).build();
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            responseObserver.onNext(response1);
            responseObserver.onNext(response2);
            responseObserver.onCompleted();
          }
        });

    @SuppressWarnings("unchecked")
    LoggingHandler<ReadRequest, ReadResponse> handler = Mockito.mock(LoggingHandler.class);
    RpcCallDetails details = RpcCallDetails.getDefaultInstance();
    Mockito.when(handler.getDetails()).thenReturn(details);
    AsynchronousFileOutputStream output = Mockito.mock(AsynchronousFileOutputStream.class);

    LoggingInterceptor interceptor = getInterceptorWithAlwaysThisHandler(handler, output);
    Channel channel =
        ClientInterceptors.intercept(
            InProcessChannelBuilder.forName(fakeServerName).directExecutor().build(), interceptor);
    ByteStreamBlockingStub stub = ByteStreamGrpc.newBlockingStub(channel);

    // Read both responses.
    advance(stub.read(request), 2);

    ArgumentCaptor<ReadResponse> resultCaptor = ArgumentCaptor.forClass(ReadResponse.class);

    LogEntry expectedEntry =
        LogEntry.newBuilder()
            .setMethodName(ByteStreamGrpc.getReadMethod().getFullMethodName())
            .setDetails(details)
            .setStatus(com.google.rpc.Status.getDefaultInstance())
            .build();

    verify(handler).handleReq(request);
    verify(handler, times(2)).handleResp(resultCaptor.capture());
    assertThat(resultCaptor.getAllValues().get(0)).isEqualTo(response1);
    assertThat(resultCaptor.getAllValues().get(1)).isEqualTo(response2);
    verify(handler).getDetails();
    verify(output).write(expectedEntry);
  }

  @Test
  public void testCallOkMultipleRequests() {
    WriteRequest request1 =
        WriteRequest.newBuilder()
            .setResourceName("test")
            .setData(ByteString.copyFromUtf8("abc"))
            .build();
    WriteRequest request2 =
        WriteRequest.newBuilder()
            .setResourceName("test")
            .setData(ByteString.copyFromUtf8("def"))
            .build();
    WriteResponse response = WriteResponse.newBuilder().setCommittedSize(6).build();
    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> streamObserver) {
            return new StreamObserver<WriteRequest>() {
              @Override
              public void onNext(WriteRequest writeRequest) {}

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onCompleted() {
                streamObserver.onNext(response);
                streamObserver.onCompleted();
              }
            };
          }
        });

    @SuppressWarnings("unchecked")
    LoggingHandler<WriteRequest, WriteResponse> handler = Mockito.mock(LoggingHandler.class);
    RpcCallDetails details = RpcCallDetails.getDefaultInstance();
    Mockito.when(handler.getDetails()).thenReturn(details);
    AsynchronousFileOutputStream output = Mockito.mock(AsynchronousFileOutputStream.class);

    LoggingInterceptor interceptor = getInterceptorWithAlwaysThisHandler(handler, output);
    Channel channel =
        ClientInterceptors.intercept(
            InProcessChannelBuilder.forName(fakeServerName).directExecutor().build(), interceptor);
    ByteStreamStub stub = ByteStreamGrpc.newStub(channel);

    @SuppressWarnings("unchecked")
    StreamObserver<WriteResponse> responseObserver = Mockito.mock(StreamObserver.class);
    // Write both responses.
    StreamObserver<WriteRequest> requester = stub.write(responseObserver);
    requester.onNext(request1);
    requester.onNext(request2);
    requester.onCompleted();

    ArgumentCaptor<WriteRequest> resultCaptor = ArgumentCaptor.forClass(WriteRequest.class);

    LogEntry expectedEntry =
        LogEntry.newBuilder()
            .setMethodName(ByteStreamGrpc.getWriteMethod().getFullMethodName())
            .setDetails(details)
            .setStatus(com.google.rpc.Status.getDefaultInstance())
            .build();

    verify(handler, times(2)).handleReq(resultCaptor.capture());
    assertThat(resultCaptor.getAllValues().get(0)).isEqualTo(request1);
    assertThat(resultCaptor.getAllValues().get(1)).isEqualTo(request2);
    verify(handler).handleResp(response);
    verify(handler).getDetails();
    verify(output).write(expectedEntry);
  }

  @Test
  public void testCallWithError() {
    ReadRequest request = ReadRequest.newBuilder().setResourceName("test").build();
    Status error = Status.NOT_FOUND.withDescription("not found");

    serviceRegistry.addService(
        new ByteStreamImplBase() {
          @Override
          public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
            responseObserver.onError(error.asRuntimeException());
          }
        });

    @SuppressWarnings("unchecked")
    LoggingHandler<ReadRequest, ReadResponse> handler = Mockito.mock(LoggingHandler.class);
    RpcCallDetails details = RpcCallDetails.getDefaultInstance();
    Mockito.when(handler.getDetails()).thenReturn(details);
    AsynchronousFileOutputStream output = Mockito.mock(AsynchronousFileOutputStream.class);

    LoggingInterceptor interceptor = getInterceptorWithAlwaysThisHandler(handler, output);
    Channel channel =
        ClientInterceptors.intercept(
            InProcessChannelBuilder.forName(fakeServerName).directExecutor().build(), interceptor);
    ByteStreamBlockingStub stub = ByteStreamGrpc.newBlockingStub(channel);

    assertThrows(StatusRuntimeException.class, () -> stub.read(request).next());

    LogEntry expectedEntry =
        LogEntry.newBuilder()
            .setMethodName(ByteStreamGrpc.getReadMethod().getFullMethodName())
            .setDetails(details)
            .setStatus(
                com.google.rpc.Status.newBuilder()
                    .setCode(error.getCode().value())
                    .setMessage(error.getDescription()))
            .build();

    verify(handler).handleReq(request);
    verify(handler, never()).handleResp(any());
    verify(handler).getDetails();
    verify(output).write(expectedEntry);
  }

  @Test
  public void testExecuteCallOk() {
    ExecuteRequest request =
        ExecuteRequest.newBuilder()
            .setInstanceName("test-instance")
            .setAction(Action.newBuilder().addOutputFiles("somefile"))
            .build();
    Operation response = Operation.newBuilder().setName("test-operation").build();
    serviceRegistry.addService(
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
          }
        });

    ExecutionBlockingStub stub = ExecutionGrpc.newBlockingStub(loggedChannel);
    stub.execute(request);
    LogEntry expectedEntry =
        LogEntry.newBuilder()
            .setMethodName(ExecutionGrpc.getExecuteMethod().getFullMethodName())
            .setDetails(
                RpcCallDetails.newBuilder()
                    .setExecute(
                        ExecuteDetails.newBuilder().setRequest(request).setResponse(response)))
            .setStatus(com.google.rpc.Status.getDefaultInstance())
            .build();
    verify(logStream).write(expectedEntry);
  }

  @Test
  public void testExecuteCallFail() {
    ExecuteRequest request =
        ExecuteRequest.newBuilder()
            .setInstanceName("test-instance")
            .setAction(Action.newBuilder().addOutputFiles("somefile"))
            .build();
    Status error = Status.NOT_FOUND.withDescription("not found");
    serviceRegistry.addService(
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            responseObserver.onError(error.asRuntimeException());
          }
        });
    ExecutionBlockingStub stub = ExecutionGrpc.newBlockingStub(loggedChannel);

    assertThrows(StatusRuntimeException.class, () -> stub.execute(request));
    LogEntry expectedEntry =
        LogEntry.newBuilder()
            .setMethodName(ExecutionGrpc.getExecuteMethod().getFullMethodName())
            .setDetails(
                RpcCallDetails.newBuilder()
                    .setExecute(ExecuteDetails.newBuilder().setRequest(request)))
            .setStatus(
                com.google.rpc.Status.newBuilder()
                    .setCode(error.getCode().value())
                    .setMessage(error.getDescription()))
            .build();
    verify(logStream).write(expectedEntry);
  }

  @Test
  public void testFindMissingBlobsCallOk() {
    Digest testDigest = DigestUtil.buildDigest("test", 8);
    FindMissingBlobsRequest request =
        FindMissingBlobsRequest.newBuilder()
            .addBlobDigests(testDigest)
            .setInstanceName("test-instance")
            .build();
    FindMissingBlobsResponse response =
        FindMissingBlobsResponse.newBuilder().addMissingBlobDigests(testDigest).build();
    serviceRegistry.addService(
        new ContentAddressableStorageImplBase() {
          @Override
          public void findMissingBlobs(
              FindMissingBlobsRequest request,
              StreamObserver<FindMissingBlobsResponse> responseObserver) {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
          }
        });

    ContentAddressableStorageBlockingStub stub =
        ContentAddressableStorageGrpc.newBlockingStub(loggedChannel);

    stub.findMissingBlobs(request);
    LogEntry expectedEntry =
        LogEntry.newBuilder()
            .setMethodName(
                ContentAddressableStorageGrpc.getFindMissingBlobsMethod().getFullMethodName())
            .setDetails(
                RpcCallDetails.newBuilder()
                    .setFindMissingBlobs(
                        FindMissingBlobsDetails.newBuilder()
                            .setRequest(request)
                            .setResponse(response)))
            .setStatus(com.google.rpc.Status.getDefaultInstance())
            .build();
    verify(logStream).write(expectedEntry);
  }

  @Test
  public void testGetActionResultCallOk() {
    Digest testDigest = DigestUtil.buildDigest("test", 8);
    GetActionResultRequest request =
        GetActionResultRequest.newBuilder()
            .setActionDigest(testDigest)
            .setInstanceName("test-instance")
            .build();
    ActionResult response =
        ActionResult.newBuilder()
            .addOutputFiles(OutputFile.newBuilder().setDigest(testDigest).setPath("root/test"))
            .setExitCode(1)
            .build();

    serviceRegistry.addService(
        new ActionCacheImplBase() {
          @Override
          public void getActionResult(
              GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
          }
        });
    ActionCacheBlockingStub stub = ActionCacheGrpc.newBlockingStub(loggedChannel);

    stub.getActionResult(request);
    LogEntry expectedEntry =
        LogEntry.newBuilder()
            .setMethodName(ActionCacheGrpc.getGetActionResultMethod().getFullMethodName())
            .setDetails(
                RpcCallDetails.newBuilder()
                    .setGetActionResult(
                        GetActionResultDetails.newBuilder()
                            .setRequest(request)
                            .setResponse(response)))
            .setStatus(com.google.rpc.Status.getDefaultInstance())
            .build();
    verify(logStream).write(expectedEntry);
  }

  @Test
  public void testWatchCallOk() {
    Request request = Request.newBuilder().setTarget("test-target").build();
    ChangeBatch response1 =
        ChangeBatch.newBuilder()
            .addChanges(Change.newBuilder().setState(Change.State.INITIAL_STATE_SKIPPED))
            .build();
    ChangeBatch response2 =
        ChangeBatch.newBuilder()
            .addChanges(
                Change.newBuilder().setState(Change.State.EXISTS).setData(Any.pack(request)))
            .build();

    serviceRegistry.addService(
        new WatcherImplBase() {
          @Override
          public void watch(Request request, StreamObserver<ChangeBatch> responseObserver) {
            responseObserver.onNext(response1);
            responseObserver.onNext(response2);
            responseObserver.onCompleted();
          }
        });

    Iterator<ChangeBatch> replies = WatcherGrpc.newBlockingStub(loggedChannel).watch(request);

    // Read both responses.
    while (replies.hasNext()) {
      replies.next();
    }

    LogEntry expectedEntry =
        LogEntry.newBuilder()
            .setMethodName(WatcherGrpc.getWatchMethod().getFullMethodName())
            .setDetails(
                RpcCallDetails.newBuilder()
                    .setWatch(
                        WatchDetails.newBuilder()
                            .setRequest(request)
                            .addResponses(response1)
                            .addResponses(response2)))
            .setStatus(com.google.rpc.Status.getDefaultInstance())
            .build();
    verify(logStream).write(expectedEntry);
  }

  @Test
  public void testWatchCallFail() {
    Request request = Request.newBuilder().setTarget("test-target").build();
    ChangeBatch response =
        ChangeBatch.newBuilder()
            .addChanges(Change.newBuilder().setState(Change.State.INITIAL_STATE_SKIPPED))
            .build();
    Status error = Status.DEADLINE_EXCEEDED.withDescription("timed out");

    serviceRegistry.addService(
        new WatcherImplBase() {
          @Override
          public void watch(Request request, StreamObserver<ChangeBatch> responseObserver) {
            responseObserver.onNext(response);
            responseObserver.onError(error.asRuntimeException());
          }
        });
    Iterator<ChangeBatch> replies = WatcherGrpc.newBlockingStub(loggedChannel).watch(request);
    assertThrows(
        StatusRuntimeException.class,
        () -> {
          while (replies.hasNext()) {
            replies.next();
          }
        });

    LogEntry expectedEntry =
        LogEntry.newBuilder()
            .setMethodName(WatcherGrpc.getWatchMethod().getFullMethodName())
            .setDetails(
                RpcCallDetails.newBuilder()
                    .setWatch(WatchDetails.newBuilder().setRequest(request).addResponses(response)))
            .setStatus(
                com.google.rpc.Status.newBuilder()
                    .setCode(error.getCode().value())
                    .setMessage(error.getDescription()))
            .build();
    verify(logStream).write(expectedEntry);
  }
}
