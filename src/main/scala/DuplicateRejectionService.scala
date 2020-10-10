
import domain.model.dupetracker.DuplicationTrackerRepository
import domain.model.dupetracker.DuplicationTrackerRepository._

import zio._
import zio.clock._
import zio.duration._

object DuplicateRejectionService {

  /**
   * Adds an element of A to the underlying collection and removes it afterwards.
   * @param element An element of A that is to be added fleetingly to the underlying collection.
   * @param n The number of seconds of delay to the removal.
   * @return A Boolean value which represents whether an element was added (and removed).
   *         true means it was added and WILL be removed (after n-seconds).
   */
  def addAndDeleteAfter[A: Tag](element: A, n: Int = 145): URIO[DuplicationTrackerRepository[A] with Clock, Boolean] =
    for {
      contains <- DuplicationTrackerRepository.add(element)
      _        <- DuplicationTrackerRepository.remove(element).unless(contains).delay(n.seconds).fork
    } yield contains

  def size[A: Tag]: URIO[DuplicationTrackerRepository[A], Int] =
    DuplicationTrackerRepository.size
}
