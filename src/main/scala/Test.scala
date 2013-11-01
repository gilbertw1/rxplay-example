package test

import rx.lang.scala._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext,Await,future}
import scala.util.{Try,Success,Failure}
import play.api.libs.iteratee._
import play.api.libs.iteratee.Concurrent.Channel
import java.io.File

import ExecutionContext.Implicits.global

object Test extends App {

  import RxPlay._

  /* Running fold iteratee on observer */
  val observer = Observable(1, 2, 3, 4, 5)
  val res = observer.run(Iteratee.fold(0) { (total, elt) => total + elt })
  println(Await.result(res, 5 seconds))

  /* Using Enumerator created from file as an observer */
  val fileEnum = Enumerator.fromFile(new File("test.txt"), 1)
  val fileObs: Observable[Array[Byte]] = fileEnum
  fileObs.map(new String(_)).subscribe(println(_))


  /* Using implicit conversion to apply composed enumeratees and iteratee to async observable */
  val filterOdd = Enumeratee.filter[Long](_ % 2 != 0)
  val takeFive = Enumeratee.take[Long](5)
  val longToString = Enumeratee.map[Long](_.toString)
  val composed = filterOdd compose takeFive compose longToString

  val asyncIntObserverable = Observable.interval(50 millis)
  asyncIntObserverable through composed run Iteratee.foreach(println(_))
}

object RxPlay {
  implicit def enumerator2Observable[T](enum: Enumerator[T]): Observable[T] = {
    Observable({ observer: Observer[T] =>
      var cancelled = false
      val cancellableEnum = enum through Enumeratee.breakE[T](_ => cancelled)

      cancellableEnum (
        Iteratee.foreach(observer.onNext(_))
      ).onComplete {
        case Success(_) => observer.onCompleted()
        case Failure(e) => observer.onError(e)
      }

      new Subscription { override def unsubscribe() = { cancelled = true } }
    })
  }

  implicit def observable2Enumerator[T](obs: Observable[T]): Enumerator[T] = {
    var subscription: Option[Subscription] = None
    Concurrent.unicast[T](onStart = { chan =>
      subscription = Some(obs.subscribe(new ChannelObserver(chan)))
    }, onComplete = {
      subscription.foreach(_.unsubscribe)
    })
  }

  class ChannelObserver[T](chan: Channel[T]) extends rx.Observer[T] {
    def onNext(arg: T): Unit = chan.push(arg)
    def onCompleted(): Unit = chan.end()
    def onError(e: Throwable): Unit = chan.end(e)
  }
}