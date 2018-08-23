import monix.eval.Task

import scala.util.control.NoStackTrace

case class InsufficientBalanceNoStackTrace(currentBalance: Long, debitAmount: Long)
    extends RuntimeException(s"Attempted to debit $debitAmount but current balance is $currentBalance")
    with NoStackTrace

class AccountService(repository: AccountStateRepository) {
  def credit(amount: Long): Task[Unit] =
    (for {
      currentState <- Task.deferFutureAction(implicit ec => repository.read)
      newState     <- Task.eval(State(currentState.version + 1, currentState.balance + amount))
      _            <- Task.deferFutureAction(implicit ec => repository.write(currentState, newState))
    } yield ()).onErrorRestartIf {
      case _: OptimisticConcurrencyException => true
    }

  def debit(amount: Long): Task[Unit] =
    (for {
      currentState <- Task.deferFutureAction(implicit ec => repository.read)
      newState <- if (amount <= currentState.balance)
        Task.eval(State(currentState.version + 1, currentState.balance - amount))
      else
        Task.raiseError(InsufficientBalanceNoStackTrace(currentState.balance, amount))
      _ <- Task.deferFutureAction(implicit ec => repository.write(currentState, newState))
    } yield ()).onErrorRestartIf {
      case _: OptimisticConcurrencyException => true
      case _                                 => false
    }

  def balance: Task[Long] = Task.deferFutureAction(implicit ec => repository.read).map(_.balance)
}
