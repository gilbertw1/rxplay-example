package test

import rx.lang.scala._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext,Await,future,Future,Promise}
import scala.util.{Try,Success,Failure}
import play.api.libs.iteratee._
import play.api.libs.iteratee.Concurrent.Channel
import java.io.File
import Enumerator.Pushee


import ExecutionContext.Implicits.global

object Test extends App {

  import RxPlay._

  // /* Running fold iteratee on observer */
  val observer = Observable(1, 2, 3, 4, 5)
  val res = observer.run(Iteratee.fold(0) { (total, elt) => total + elt })
  println(Await.result(res, 5 seconds))

  // /* Using Enumerator created from file as an observer */
  val fileEnum = Enumerator.fromFile(new File("test.txt"), 1)
  val fileObs: Observable[Array[Byte]] = fileEnum
  fileObs.map(new String(_)).subscribe(println(_))

  //  Using implicit conversion to apply composed enumeratees and iteratee to async observable 
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
    unicast[T] { (chan) =>
      val subscription = obs.subscribe(new ChannelObserver(chan))
      val onComplete = { () => subscription.unsubscribe }
      val onError = { (_: String, _: Input[T]) => subscription.unsubscribe }
      (onComplete, onError)
    }
  }

  class ChannelObserver[T](chan: Channel[T]) extends rx.Observer[T] {
    def onNext(arg: T): Unit = chan.push(arg)
    def onCompleted(): Unit = chan.end()
    def onError(e: Throwable): Unit = chan.end(e)
  }


  def unicast[E] (onStart: Channel[E] => (() => Unit, (String, Input[E]) => Unit)) = new Enumerator[E] {

    import scala.concurrent.stm.Ref

    def apply[A](it: Iteratee[E, A]): Future[Iteratee[E, A]] = {
      val promise: scala.concurrent.Promise[Iteratee[E, A]] = Promise[Iteratee[E, A]]()
      val iteratee: Ref[Future[Option[Input[E] => Iteratee[E, A]]]] = Ref(it.pureFold { case  Step.Cont(k) => Some(k); case other => promise.success(other.it); None})
      val onCompletePromise: scala.concurrent.Promise[() => Unit] = Promise[() => Unit]()
      val onErrorPromise: scala.concurrent.Promise[(String, Input[E]) => Unit] = Promise[(String, Input[E]) => Unit]()

      val pushee = new Channel[E] {
        def close() {
          iteratee.single.swap(Future.successful(None)).onComplete{
            case Success(maybeK) => maybeK.foreach { k => 
              promise.success(k(Input.EOF))
            }
            case Failure(e) => promise.failure(e)
          }
        }

        def end(e: Throwable) {
          iteratee.single.swap(Future.successful(None)).onComplete { 
            case Success(maybeK) =>
              maybeK.foreach(_ => promise.failure(e))
            case Failure(e) => promise.failure(e)
          }
        }

        def end() {
          iteratee.single.swap(Future.successful(None)).onComplete { maybeK =>
            maybeK.get.foreach(k => promise.success(Cont(k)))
          }
        }

        def push(item: Input[E]) {
          val eventuallyNext = Promise[Option[Input[E] => Iteratee[E,A]]]()
          iteratee.single.swap(eventuallyNext.future).onComplete {
            case Success(None) => eventuallyNext.success(None)
            case Success(Some(k)) =>
               val n = {
                  val next = k(item)
                  next.pureFold {
                    case Step.Done(a, in) => {
                      onCompletePromise.future.foreach(_())
                      promise.success(next)
                      None
                    }
                    case Step.Error(msg, e) =>
                      onErrorPromise.future.foreach(_(msg, e))
                      promise.success(next)
                      None
                    case Step.Cont(k) =>
                      Some(k)
                  }
                }
              eventuallyNext.completeWith(n)
          case Failure(e) => 
            promise.failure(e)
            eventuallyNext.success(None)
          }
        }
      }
      val (onComplete, onError) = onStart(pushee)
      onCompletePromise.success(onComplete)
      onErrorPromise.success(onError)
      promise.future
    }
  }
}