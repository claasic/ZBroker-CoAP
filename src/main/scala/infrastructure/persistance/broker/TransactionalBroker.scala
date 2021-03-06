package infrastructure.persistance.broker

import domain.model.broker.BrokerRepository
import domain.model.broker.BrokerRepository._
import domain.model.exception._
import domain.model.exception.MissingBrokerBucket._
import domain.model.exception.MissingSubscriber._

import zio.stm._
import zio._

/**
 * A broker which handles topics and subscribers. Topics are paired with their subscribers and
 * each subscriber has its own mail system which is a queue.
 * @param mailboxes A thread-safe bucket system for each incoming connection represented as a
 *                  Long where the connection is paired with its own queue to push messages into.
 * @param subscriptions A thread-safe subscription system where the key represents a topic and
 *                      its subscribers are saved inside the paired Set. A subscriber is
 *                      identified by its unique Long value.
 * @param subscribers A TMap which maps each Subscriber to a Set of its subscribed topics
 * @param counter A thread-safe counter that acts as supplier for unique connection ID's.
 * @tparam R The type of the response to an external subscriber, usually dependent on protobuf
 */
class TransactionalBroker[R] private (
  private val mailboxes: TMap[Long, TQueue[R]],
  private val subscriptions: TMap[String, Set[Long]],
  private val subscribers: TMap[Long, Set[String]],
  private val counter: TRef[Long]
) extends BrokerRepository.Service[R] {
  import TransactionalBroker._

  /**
   * Increments and then returns the next ID while being thread-safe.
   */
  val getNextId: UIO[Long] = counter.updateAndGet(_ + 1).commit

  /**
   * Maps a new subscriber to one or multiple topics.
   * Additionally, all new subscriptions are mapped to the subscriber.
   *
   * @param topics A sequence of topics that the user wants to subscribe to.
   * @param id     The connection id of the user.
   */
  def addSubscriberTo(topics: Paths, id: Long): UIO[Unit] =
    STM.atomically {
      for {
        _    <- STM.unlessM(mailboxes.contains(id))(TQueue.unbounded[R] >>= (mailboxes.put(id, _)))
        keys =  topics.map(getPathFromSegments)
        _    <- subscribers.merge(id, keys.toSet)(_ union _)
        _    <- STM.foreach_(keys)(key => subscriptions.merge(key, Set(id))(_ union _))
      } yield ()
    }

  /**
   * Attempts to retrieve the subscribers from a singular topic
   *
   * @return An Option of the Set of said topic's subscribers
   */
  def getSubscribers(topic: String): UIO[Option[Set[Long]]] =
    subscriptions.get(topic).commit

  def getSubscribers(topic: Segments): UIO[Option[Set[Long]]] =
    STM.atomically {
      for {
        k <- STM.succeed(getPathFromSegments(topic))
        r <- subscriptions.contains(k) >>= (if (_) subscriptions.get(k) else STM.none)
      } yield r
    }

  /**
   * Attempts to get the mailbox (a queue) mapped to the specified ID.
   * <p>
   * WARNING: MULTIPLE EXTRACTIONS AND CONSUMPTIONS ARE NOT CHECKED FOR.
   * @param id The connection ID, used as a key value to get the queue.
   * @return Either a TQueue as planned or an UnexpectedError which represents a very faulty system state.
   */
  def getQueue(id: Long): IO[MissingBrokerBucket, TQueue[R]] =
    STM.atomically {
      for {
        bool  <- mailboxes.contains(id)
        _     <- if (bool) STM.unit else STM.retry
        queue <- mailboxes.get(id).flatMap(STM.fromOption(_)).mapError(_ => MissingBrokerBucket)
      } yield queue
    }

  /**
   * Pushes a message to all its related topic's subscribers by acquiring all subscribers from
   * all sub-routes that result from the given route. Then those subscribers are merged these
   * into a set to avoid duplicate messages. The message is then offered to each
   * subscribers personal queue.
   *
   * @param uriPath An URI path which represents the topic to which the message is addressed.
   * @param msg     The message - already converted into the PublisherResponse format.
   */
  def pushMessageTo(uriPath: Segments, msg: R): UIO[Unit] =
    STM.atomically {
      for {
        routes <- STM.succeed(TransactionalBroker.getSubPaths(uriPath))
        set    <- STM.foreach(routes)(subscriptions.getOrElse(_, Set.empty[Long])).map(_.reduce(_ union _))
        _      <- STM.foreach_(set) { key =>
          for {
            queueM <- mailboxes.get(key)
            queue  <- queueM.fold(TQueue.unbounded[R])(STM.succeed(_))
            _      <- queue.offer(msg)
          } yield ()
        }
      } yield ()
    }

  /**
   * Adds a URI path and it's sub-routes to the TransactionalBroker.
   */
  def addTopic(uriPath: Segments): UIO[Unit] =
    STM.atomically {
      for {
        keys <- STM.succeed(TransactionalBroker.getSubPaths(uriPath))
        _    <- STM.foreach_(keys)(subscriptions.putIfAbsent(_, Set.empty[Long]))
      } yield ()
    }

  /**
   * Removes a subscriber by its ID from all its subscribed topics and deletes its mailbox.
   * <p>
   * WARNING: Technically, a queue should be deleted by its consumer. Yet, this is NOT checked!
   *
   * @param id The unique ID of a subscriber
   */
  def removeSubscriber(id: Long): IO[MissingSubscriber, Unit] =
    STM.atomically {
      for {
        topics <- subscribers.get(id) >>= (o => STM.fromOption(o).mapError(_ => MissingSubscriber))
        _      <- STM.foreach_(topics)(topic => subscriptions.merge(topic, Set(id))(_ diff _))
        _      <- mailboxes.delete(id)
        _      <- subscribers.delete(id)
      } yield ()
    }

  /**
   * Removes one or many subscriptions of a given subscriber but does NOT delete its mailbox
   * or necessarily all its subscriptions.
   * @param topics A NonEmptyChunk of NonEmptyChunks were each sub-chunk contains a topic in a segmented form.
   * @param id The id of the subscriber that wants to delete some subscriptions of itself.
   */
  def removeSubscriptions(topics: Paths, id: Long): UIO[Unit] =
    STM.atomically {
      for {
        paths <- STM.succeed(topics.map(getPathFromSegments))
        _     <- STM.foreach_(paths) { path =>
          STM.whenM(subscriptions.get(path).map(_.fold(false)(_.contains(id)))) {
            subscriptions.merge(path, Set(id))(_ diff _)
          }
        }
      } yield ()
    }

  val getTopics: UIO[List[String]] =
    subscriptions.keys.commit


  /*
   * The following functions provide access to different lengths and sizes of the Broker.
   * These functions primarily exist for testing.
   */
  def subscriptionSizeOf(key: String): UIO[Int] = subscriptions.get(key).map(_.size).commit
  def subscriberElements(key: Long): UIO[Int]   = subscribers.get(key).map(_.size).commit
  def mailboxOf(key: Long): UIO[Int] = mailboxes.get(key).flatMap(_.fold(STM.succeed(0))(_.size)).commit

  val sizeMailboxes     = mailboxes.size.commit
  val sizeSubscriptions = subscriptions.size.commit
  val sizeSubscribers   = subscribers.size.commit
  val sizeCounter       = counter.get.commit
}

object TransactionalBroker {

  /**
   * Takes the segments which make up a path and returns their sub-routes.
   */
  private def getSubPaths(segments: Segments): Seq[String] =
    cleanSegments(segments).scanLeft("") { (acc, c) =>
      if (acc.isBlank) c else acc + "/" + c
    }.tail

  /**
   * Takes segments which make up a path and returns the complete path as a String.
   */
  private def getPathFromSegments(uriPath: Segments): String =
    cleanSegments(uriPath).mkString("/")

  private def cleanSegments(segments: Segments): Chunk[String] =
    segments.map(_.filterNot(_ == '/')).filterNot(_.isEmpty)

  /**
   * Creates a STM of a TransactionalBroker.
   */
  def make[R]: USTM[TransactionalBroker[R]] =
    for {
      buckets <- TMap.empty[Long, TQueue[R]]
      topics  <- TMap.empty[String, Set[Long]]
      subs    <- TMap.empty[Long, Set[String]]
      counter <- TRef.make(0L)
      repo    =  new TransactionalBroker(buckets, topics, subs, counter)
    } yield repo

}
