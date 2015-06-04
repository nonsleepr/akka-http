/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.client

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ SocketChannel, ServerSocketChannel }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }
import akka.util.ByteString
import akka.http.scaladsl.{ TestUtils, Http }
import akka.http.impl.util.{ SingletonException, StreamUtils }
import akka.http.{ ClientConnectionSettings, ConnectionPoolSettings, ServerSettings }
import akka.stream.io.{ SessionBytes, SendBytes, SslTlsInbound, SslTlsOutbound }
import akka.stream.{ BidiShape, ActorFlowMaterializer }
import akka.stream.testkit.{ TestPublisher, TestSubscriber, AkkaSpec }
import akka.stream.scaladsl._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model._

class ConnectionPoolSpec extends AkkaSpec("""
    akka.loggers = []
    akka.loglevel = OFF
    akka.io.tcp.trace-logging = off
    akka.io.tcp.windows-connection-abort-workaround-enabled=auto""") {
  implicit val materializer = ActorFlowMaterializer()

  // FIXME: Extract into proper util class to be reusable
  lazy val ConnectionResetByPeerMessage: String = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.socket.bind(new InetSocketAddress("127.0.0.1", 0))
    try {
      val clientSocket = SocketChannel.open(new InetSocketAddress("127.0.0.1", serverSocket.socket().getLocalPort))
      @volatile var serverSideChannel: SocketChannel = null
      awaitCond {
        serverSideChannel = serverSocket.accept()
        serverSideChannel != null
      }
      serverSideChannel.socket.setSoLinger(true, 0)
      serverSideChannel.close()
      clientSocket.read(ByteBuffer.allocate(1))
      null
    } catch {
      case NonFatal(e) ⇒ e.getMessage
    }
  }

  "The host-level client infrastructure" should {

    "properly complete a simple request/response cycle" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/") -> 42)

      acceptIncomingConnection()
      responseOutSub.request(1)
      val (Success(response), 42) = responseOut.expectNext()
      response.headers should contain(RawHeader("Req-Host", s"$serverHostName:$serverPort"))
    }

    "open a second connection if the first one is loaded" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = "/b") -> 43)

      responseOutSub.request(2)
      acceptIncomingConnection()
      val r1 = responseOut.expectNext()
      acceptIncomingConnection()
      val r2 = responseOut.expectNext()

      Seq(r1, r2) foreach {
        case (Success(x), 42) ⇒ requestUri(x) should endWith("/a")
        case (Success(x), 43) ⇒ requestUri(x) should endWith("/b")
        case x                ⇒ fail(x.toString)
      }
      Seq(r1, r2).map(t ⇒ connNr(t._1.get)) should contain allOf (1, 2)
    }

    "not open a second connection if there is an idle one available" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      acceptIncomingConnection()
      responseOutSub.request(1)
      val (Success(response1), 42) = responseOut.expectNext()
      connNr(response1) shouldEqual 1

      requestIn.sendNext(HttpRequest(uri = "/b") -> 43)
      responseOutSub.request(1)
      val (Success(response2), 43) = responseOut.expectNext()
      connNr(response2) shouldEqual 1
    }

    "be able to handle 500 pipelined requests against the test server" in new TestSetup {
      val settings = ConnectionPoolSettings(system).copy(maxConnections = 4, pipeliningLimit = 2)
      val poolFlow = Http().cachedHostConnectionPool[Int](serverHostName, serverPort, settings = settings)

      val N = 500
      val requestIds = Source(() ⇒ Iterator.from(1)).take(N)
      val idSum = requestIds.map(id ⇒ HttpRequest(uri = s"/r$id") -> id).via(poolFlow).map {
        case (Success(response), id) ⇒
          requestUri(response) should endWith(s"/r$id")
          id
        case x ⇒ fail(x.toString)
      }.runFold(0)(_ + _)

      acceptIncomingConnection()
      acceptIncomingConnection()
      acceptIncomingConnection()
      acceptIncomingConnection()

      Await.result(idSum, 10.seconds) shouldEqual N * (N + 1) / 2
    }

    "properly surface connection-level errors" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int](maxRetries = 0)

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = "/crash") -> 43)
      responseOutSub.request(2)

      override def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString =
        if (bytes.utf8String.contains("/crash")) sys.error("CRASH BOOM BANG") else bytes

      val responses = Seq(responseOut.expectNext(), responseOut.expectNext())

      responses mustContainLike { case (Success(x), 42) ⇒ requestUri(x) should endWith("/a") }
      responses mustContainLike { case (Failure(x), 43) ⇒ x.getMessage should include(ConnectionResetByPeerMessage) }
    }

    "retry failed requests" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = "/crash") -> 43)
      responseOutSub.request(2)

      val remainingResponsesToKill = new AtomicInteger(1)
      override def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString =
        if (bytes.utf8String.contains("/crash") && remainingResponsesToKill.decrementAndGet() >= 0)
          sys.error("CRASH BOOM BANG")
        else bytes

      val responses = Seq(responseOut.expectNext(), responseOut.expectNext())

      responses mustContainLike { case (Success(x), 42) ⇒ requestUri(x) should endWith("/a") }
      responses mustContainLike { case (Success(x), 43) ⇒ requestUri(x) should endWith("/crash") }
    }

    "respect the configured `maxRetries` value" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int](maxRetries = 4)

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = "/crash") -> 43)
      responseOutSub.request(2)

      val remainingResponsesToKill = new AtomicInteger(5)
      override def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString =
        if (bytes.utf8String.contains("/crash") && remainingResponsesToKill.decrementAndGet() >= 0)
          sys.error("CRASH BOOM BANG")
        else bytes

      val responses = Seq(responseOut.expectNext(), responseOut.expectNext())

      responses mustContainLike { case (Success(x), 42) ⇒ requestUri(x) should endWith("/a") }
      responses mustContainLike { case (Failure(x), 43) ⇒ x.getMessage should include(ConnectionResetByPeerMessage) }
      remainingResponsesToKill.get() shouldEqual 0
    }

    "automatically shutdown after configured timeout periods" in new TestSetup() {
      val (_, _, _, hcp) = cachedHostConnectionPool[Int](idleTimeout = 1.second)
      val gateway = Await.result(hcp.gatewayFuture, 500.millis)
      val PoolGateway.Running(_, shutdownStartedPromise, shutdownCompletedPromise) = gateway.currentState
      shutdownStartedPromise.isCompleted shouldEqual false
      shutdownCompletedPromise.isCompleted shouldEqual false
      Await.result(shutdownStartedPromise.future, 1500.millis) shouldEqual () // verify shutdown start (after idle)
      Await.result(shutdownCompletedPromise.future, 1500.millis) shouldEqual () // verify shutdown completed
    }

    "transparently restart after idle shutdown" in new TestSetup() {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int](idleTimeout = 1.second)

      val gateway = Await.result(hcp.gatewayFuture, 500.millis)
      val PoolGateway.Running(_, _, shutdownCompletedPromise) = gateway.currentState
      Await.result(shutdownCompletedPromise.future, 1500.millis) shouldEqual () // verify shutdown completed

      requestIn.sendNext(HttpRequest(uri = "/") -> 42)

      acceptIncomingConnection()
      responseOutSub.request(1)
      val (Success(_), 42) = responseOut.expectNext()
    }
  }

  "The single-request client infrastructure" should {
    class LocalTestSetup extends TestSetup(ServerSettings(system).copy(rawRequestUriHeader = true), autoAccept = true)

    "transform absolute request URIs into relative URIs plus host header" in new LocalTestSetup {
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort/abc?query#fragment")
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second).headers
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "/abc?query"))
      responseHeaders should contain(RawHeader("Req-Host", s"$serverHostName:$serverPort"))
    }

    "support absolute request URIs without path component" in new LocalTestSetup {
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort")
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second).headers
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "/"))
    }

    "support absolute request URIs with a double slash path component" in new LocalTestSetup {
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort//foo")
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second).headers
      responseHeaders should contain(RawHeader("Req-Uri", s"http://$serverHostName:$serverPort//foo"))
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "//foo"))
    }

    "produce an error if the request does not have an absolute URI" in {
      val request = HttpRequest(uri = "/foo")
      val responseFuture = Http().singleRequest(request)
      val thrown = the[IllegalUriException] thrownBy Await.result(responseFuture, 1.second)
      thrown should have message "Cannot determine request scheme and target endpoint as HttpMethod(GET) request to /foo doesn't have an absolute URI"
    }
  }

  "The superPool client infrastructure" should {

    "route incoming requests to the right cached host connection pool" in new TestSetup(autoAccept = true) {
      val (serverEndpoint2, serverHostName2, serverPort2) = TestUtils.temporaryServerHostnameAndPort()
      Http().bindAndHandleSync(testServerHandler(0), serverHostName2, serverPort2)

      val (requestIn, responseOut, responseOutSub, hcp) = superPool[Int]()

      requestIn.sendNext(HttpRequest(uri = s"http://$serverHostName:$serverPort/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = s"http://$serverHostName2:$serverPort2/b") -> 43)

      responseOutSub.request(2)
      Seq(responseOut.expectNext(), responseOut.expectNext()) foreach {
        case (Success(x), 42) ⇒ requestUri(x) shouldEqual s"http://$serverHostName:$serverPort/a"
        case (Success(x), 43) ⇒ requestUri(x) shouldEqual s"http://$serverHostName2:$serverPort2/b"
        case x                ⇒ fail(x.toString)
      }
    }
  }

  class TestSetup(serverSettings: ServerSettings = ServerSettings(system),
                  autoAccept: Boolean = false) {
    val (serverEndpoint, serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()

    def testServerHandler(connNr: Int): HttpRequest ⇒ HttpResponse = {
      case HttpRequest(_, uri, headers, entity, _) ⇒
        val responseHeaders =
          ConnNrHeader(connNr) +:
            RawHeader("Req-Uri", uri.toString) +: headers.map(h ⇒ RawHeader("Req-" + h.name, h.value))
        HttpResponse(headers = responseHeaders, entity = entity)
    }

    def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString = bytes

    val incomingConnectionCounter = new AtomicInteger
    val incomingConnections = TestSubscriber.manualProbe[Http.IncomingConnection]
    val incomingConnectionsSub = {
      val rawBytesInjection = BidiFlow() { b ⇒
        val top = b.add(Flow[SslTlsOutbound].collect[ByteString] { case SendBytes(x) ⇒ mapServerSideOutboundRawBytes(x) }
          .transform(StreamUtils.recover { case NoErrorComplete ⇒ ByteString.empty }))
        val bottom = b.add(Flow[ByteString].map(SessionBytes(null, _)))
        BidiShape(top.inlet, top.outlet, bottom.inlet, bottom.outlet)
      }
      val sink = if (autoAccept) Sink.foreach[Http.IncomingConnection](handleConnection) else Sink(incomingConnections)
      // TODO getHostString in Java7
      Tcp().bind(serverEndpoint.getHostName, serverEndpoint.getPort, idleTimeout = serverSettings.timeouts.idleTimeout)
        .map { c ⇒
          val layer = Http().serverLayer(serverSettings, log)
          Http.IncomingConnection(c.localAddress, c.remoteAddress, layer atop rawBytesInjection join c.flow)
        }.runWith(sink)
      if (autoAccept) null else incomingConnections.expectSubscription()
    }

    def acceptIncomingConnection(): Unit = {
      incomingConnectionsSub.request(1)
      val conn = incomingConnections.expectNext()
      handleConnection(conn)
    }

    private def handleConnection(c: Http.IncomingConnection) =
      c.handleWithSyncHandler(testServerHandler(incomingConnectionCounter.incrementAndGet()))

    def cachedHostConnectionPool[T](maxConnections: Int = 2,
                                    maxRetries: Int = 2,
                                    maxOpenRequests: Int = 8,
                                    pipeliningLimit: Int = 1,
                                    idleTimeout: Duration = 5.seconds,
                                    ccSettings: ClientConnectionSettings = ClientConnectionSettings(system)) = {
      val settings = ConnectionPoolSettings(maxConnections, maxRetries, maxOpenRequests, pipeliningLimit,
        idleTimeout, ClientConnectionSettings(system))
      flowTestBench(Http().cachedHostConnectionPool[T](serverHostName, serverPort, settings))
    }

    def superPool[T](maxConnections: Int = 2,
                     maxRetries: Int = 2,
                     maxOpenRequests: Int = 8,
                     pipeliningLimit: Int = 1,
                     idleTimeout: Duration = 5.seconds,
                     ccSettings: ClientConnectionSettings = ClientConnectionSettings(system)) = {
      val settings = ConnectionPoolSettings(maxConnections, maxRetries, maxOpenRequests, pipeliningLimit,
        idleTimeout, ClientConnectionSettings(system))
      flowTestBench(Http().superPool[T](settings))
    }

    def flowTestBench[T, Mat](poolFlow: Flow[(HttpRequest, T), (Try[HttpResponse], T), Mat]) = {
      val requestIn = TestPublisher.probe[(HttpRequest, T)]()
      val responseOut = TestSubscriber.manualProbe[(Try[HttpResponse], T)]
      val hcp = Source(requestIn).viaMat(poolFlow)(Keep.right).toMat(Sink(responseOut))(Keep.left).run()
      val responseOutSub = responseOut.expectSubscription()
      (requestIn, responseOut, responseOutSub, hcp)
    }

    def connNr(r: HttpResponse): Int = r.headers.find(_ is "conn-nr").get.value.toInt
    def requestUri(r: HttpResponse): String = r.headers.find(_ is "req-uri").get.value
  }

  case class ConnNrHeader(nr: Int) extends CustomHeader {
    def name = "Conn-Nr"
    def value = nr.toString
  }

  implicit class MustContain[T](specimen: Seq[T]) {
    def mustContainLike(pf: PartialFunction[T, Unit]): Unit =
      specimen.collectFirst(pf) getOrElse fail("did not contain")
  }

  object NoErrorComplete extends SingletonException
}
