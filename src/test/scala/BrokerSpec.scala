
import domain.model.broker.BrokerRepository
import infrastructure.environment.BrokerRepositoryEnvironment
import subgrpc.subscription.PublisherResponse
import zio.NonEmptyChunk

import zio.test._
import zio.test.Assertion._


object BrokerSpec extends DefaultRunnableSpec {

  val testEnvironment = BrokerRepositoryEnvironment.fromSTM[PublisherResponse]

  override def spec =
    suite("TransactionalBroker")(
      suite("topics")(
        testM("add subscriber") {
          for {
            _ <- BrokerRepository.addSubscriberTo[PublisherResponse](NonEmptyChunk(NonEmptyChunk("root", "node", "leaf")), 1L)
            s <- BrokerRepository.getSubscribers[PublisherResponse]("rootnodeleaf").map(a => a.fold(-1)(_.size))
          } yield assert(s)(equalTo(1))
        },
        testM("add same subscriber twice") {
          for {
            _ <- BrokerRepository.addSubscriberTo[PublisherResponse](NonEmptyChunk(NonEmptyChunk("root", "node", "leaf")), 1L).repeatN(2)
            s <- BrokerRepository.getSubscribers[PublisherResponse]("rootnodeleaf").map(a => a.fold(-1)(_.size))
          } yield assert(s)(equalTo(1))
        },
        testM("add two different subscriber to same topic") {
          for {
            _ <- BrokerRepository.addSubscriberTo[PublisherResponse](NonEmptyChunk(NonEmptyChunk("root", "node", "leaf")), 1L)
            _ <- BrokerRepository.addSubscriberTo[PublisherResponse](NonEmptyChunk(NonEmptyChunk("root", "node", "leaf")), 2L)
            s <- BrokerRepository.getSubscribers[PublisherResponse]("rootnodeleaf").map(a => a.fold(-1)(_.size))
          } yield assert(s)(equalTo(2))
        },
        testM("remove subscriber") {
          for {
            _ <- BrokerRepository.addSubscriberTo[PublisherResponse](NonEmptyChunk(NonEmptyChunk("root", "node", "leaf")), 1L)
            _ <- BrokerRepository.removeSubscriber[PublisherResponse](1L)
            s <- BrokerRepository.getSubscribers[PublisherResponse]("rootnodeleaf").map(a => a.fold(-1)(_.size))
          } yield assert(s)(equalTo(0))
        }
      ),
      suite("subscriber")(

      ),
      suite("mailbox")(

      ),
      suite("counter")(
        testM("increment") {
          for {
            a <- BrokerRepository.getNextId[PublisherResponse]
            b <- BrokerRepository.getNextId[PublisherResponse]
          } yield assert(a)(isLessThan(b))
        }
      )
    ).provideCustomLayer(testEnvironment)
}