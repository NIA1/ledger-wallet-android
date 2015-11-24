/**
 *
 * WalletProxy
 * Ledger wallet
 *
 * Created by Pierre Pollastri on 24/11/15.
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Ledger
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */
package co.ledger.wallet.wallet.proxy

import android.content.{Intent, ComponentName, ServiceConnection, Context}
import android.os.{Handler, IBinder}
import co.ledger.wallet.service.wallet.WalletService
import co.ledger.wallet.wallet.{Account, Wallet}
import de.greenrobot.event.EventBus
import org.bitcoinj.core.{Transaction, Coin}
import co.ledger.wallet.core.concurrent.ExecutionContext.Implicits.main

import scala.concurrent.{Promise, Future}

class WalletProxy(val context: Context, val name: String) extends Wallet {

  override def synchronize(): Future[Unit] = connect().flatMap(_.synchronize())

  override def accounts(): Future[Array[Account]] = connect().flatMap(_.accounts())

  override def account(index: Int): Future[Account] = connect().flatMap(_.account(index))

  override def transactions(): Future[Set[Transaction]] = connect().flatMap(_.transactions())

  override def balance(): Future[Coin] = connect().flatMap(_.balance())

  val eventBus =  EventBus
    .builder()
    .throwSubscriberException(true)
    .sendNoSubscriberEvent(false)
    .build()

  def bind(): Unit = {
    connect().map({ (wallet) =>
      wallet.eventBus.register(this)
    })
  }

  def unbind(): Unit = {
    connect().map({(wallet) =>
      wallet.eventBus.unregister(this)
    }).onComplete {
      case anything => _connection = None
    }
  }

  def onEvent(event: AnyRef): Unit = {
    eventBus.post(event)
  }

  private[this] def connect(): Future[Wallet] = {
    _connection.orElse({
      _connection = Option(new BinderServiceConnection)
      context.bindService(new Intent(context, classOf[WalletService]), _connection.get, Context.BIND_AUTO_CREATE)
      _connection
    }).get.binder.map({ (binder) =>
      binder.service.wallet(name)
    })
  }

  new Handler().postDelayed(new Runnable {
    override def run(): Unit = eventBus.post("toto")
  }, 3000)

  private [this] var _connection: Option[BinderServiceConnection] = None


  private[this] class BinderServiceConnection extends ServiceConnection {
    private val promise = Promise[WalletService#Binder]()
    def binder: Future[WalletService#Binder] = promise.future
    override def onServiceDisconnected(name: ComponentName): Unit = {}

    override def onServiceConnected(name: ComponentName, service: IBinder): Unit = promise
      .success(service.asInstanceOf[WalletService#Binder])
  }
}