/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.{ ExtendedActorSystem, ActorIdentity, ActorRef, Identify }
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import akka.stream.impl.SubscriptionTimeoutException
import akka.stream.testkit._
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._

class SubstreamSubscriptionTimeoutSpec(conf: String) extends AkkaSpec(conf) {

  def this(subscriptionTimeout: FiniteDuration) {
    this(
      s"""
          |akka.stream.materializer {
          |  subscription-timeout {
          |    mode = cancel
          |
          |    timeout = ${subscriptionTimeout.toMillis}ms
          |  }
          |}""".stripMargin)
  }

  def this() {
    this(300.millis)
  }

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)
    .withFanOutBuffer(initialSize = 2, maxSize = 2)

  implicit val dispatcher = system.dispatcher
  implicit val materializer = FlowMaterializer(settings)

  "groupBy" must {

    "timeout and cancel substream publishers when no-one subscribes to them after some time (time them out)" in {
      val publisherProbe = StreamTestKit.PublisherProbe[Int]()
      val publisher = Source(publisherProbe).groupBy(_ % 3).runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[(Int, Source[Int])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)
      upstreamSubscription.sendNext(2)
      upstreamSubscription.sendNext(3)

      val (_, s1) = subscriber.expectNext()
      // should not break normal usage
      val s1SubscriberProbe = StreamTestKit.SubscriberProbe[Int]()
      s1.runWith(Sink.publisher).subscribe(s1SubscriberProbe)
      s1SubscriberProbe.expectSubscription().request(100)
      s1SubscriberProbe.expectNext(1)

      val (_, s2) = subscriber.expectNext()
      // should not break normal usage
      val s2SubscriberProbe = StreamTestKit.SubscriberProbe[Int]()
      s2.runWith(Sink.publisher).subscribe(s2SubscriberProbe)
      s2SubscriberProbe.expectSubscription().request(100)
      s2SubscriberProbe.expectNext(2)

      val (_, s3) = subscriber.expectNext()

      // sleep long enough for it to be cleaned up
      Thread.sleep(1000)

      val f = s3.runWith(Sink.future).recover { case _: SubscriptionTimeoutException ⇒ "expected" }
      Await.result(f, 300.millis) should equal("expected")
    }

    "timeout and stop groupBy parent actor if none of the substreams are actually consumed" in {
      val publisherProbe = StreamTestKit.PublisherProbe[Int]()
      val publisher = Source(publisherProbe).groupBy(_ % 2).runWith(Sink.publisher)
      val subscriber = StreamTestKit.SubscriberProbe[(Int, Source[Int])]()
      publisher.subscribe(subscriber)

      val upstreamSubscription = publisherProbe.expectSubscription()

      val downstreamSubscription = subscriber.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)
      upstreamSubscription.sendNext(2)
      upstreamSubscription.sendNext(3)
      upstreamSubscription.sendComplete()

      val (_, s1) = subscriber.expectNext()
      val (_, s2) = subscriber.expectNext()

      val groupByActor = watchGroupByActor(5) // update this number based on how many streams the test above has...

      // it should be terminated after none of it's substreams are used within the timeout
      expectTerminated(groupByActor, 1000.millis)
    }
  }

  private def watchGroupByActor(flowNr: Int): ActorRef = {
    implicit val t = Timeout(300.millis)
    import akka.pattern.ask
    val path = s"/user/$$a/flow-${flowNr}-1-groupBy"
    val gropByPath = system.actorSelection(path)
    val groupByActor = try {
      Await.result((gropByPath ? Identify("")).mapTo[ActorIdentity], 300.millis).ref.get
    } catch {
      case ex: Exception ⇒
        alert(s"Unable to find groupBy actor by path: [$path], please adjust it's flowId, here's the current actor tree:\n" +
          system.asInstanceOf[ExtendedActorSystem].printTree)
        throw ex
    }
    watch(groupByActor)
  }

}
