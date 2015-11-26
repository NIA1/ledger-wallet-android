/**
 *
 * SpvAccountClient
 * Ledger wallet
 *
 * Created by Pierre Pollastri on 25/11/15.
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
package co.ledger.wallet.service.wallet.spv

import java.util.concurrent.atomic.AtomicReference

import co.ledger.wallet.core.concurrent.ThreadPoolTask
import co.ledger.wallet.core.utils.logs.Logger
import co.ledger.wallet.wallet.events.WalletEvents.AccountCreated
import co.ledger.wallet.wallet.exceptions.AccountHasNoXpubException
import co.ledger.wallet.wallet.{ExtendedPublicKeyProvider, Wallet, Account}
import org.bitcoinj.core.{PeerGroup, Transaction, Coin, Address}
import org.bitcoinj.crypto.DeterministicKey
import org.json.JSONObject
import scala.collection.JavaConverters._
import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}

class SpvAccountClient(val wallet: SpvWalletClient, val index: Int)
  extends Account {

  implicit val ec = wallet.ec // Work on the wallet queue

  val XpubKey = "xpub"

  type JWallet = org.bitcoinj.core.Wallet
  type JSpvBlockChain = org.bitcoinj.core.BlockChain
  type JPeerGroup = PeerGroup

  override def synchronize(): Future[Unit] = ???

  override def xpub(): Future[DeterministicKey] = load().map(_ => _xpub.get)

  override def importXpub(provider: ExtendedPublicKeyProvider): Future[Unit] =
    provider.generateXpub("") flatMap { (key) =>
      _xpub = Some(key)
      val promise = Promise[Unit]()
      wallet.accountPersistedJson(index).flatMap({ (json) =>
        json.put(XpubKey, key.serializePubB58(wallet.networkParameters))
        promise.success()
        wallet.eventBus.post(AccountCreated(index))
        wallet.save()
      })
      promise.future
    }

  override def transactions(): Future[Set[Transaction]] =
    load().map(_.getTransactions(false).asScala.toSet)

  override def balance(): Future[Coin] = load().map({(wallet) => wallet.getBalance})

  override def freshPublicAddress(): Future[Address] = load().map({(wallet) => wallet.freshReceiveAddress()})

  private def load(): Future[JWallet] = Future.successful() flatMap { (_) =>
    if (_walletFuture == null) {
      // If no xpub fail
      _walletFuture = wallet.accountPersistedJson(index) flatMap {(json) =>
        _persistentState = json
        if (_xpub.isEmpty && !json.has(XpubKey)) {
          _walletFuture = null
          throw new AccountHasNoXpubException(index)
        } else if (_xpub.isEmpty) {
          _xpub = Some(DeterministicKey.deserializeB58(json.getString(XpubKey), wallet.networkParameters))
        }
        wallet.peerGroup().map({(peerGroup) =>
          val w = org.bitcoinj.core.Wallet.fromWatchingKey(wallet.networkParameters, _xpub.get)
          wallet.blockChain.addWallet(w)
          peerGroup.addWallet(w)
          w
        })
      }
    }
    _walletFuture
  }

  private var _walletFuture: Future[JWallet] = null
  private var _persistentState: JSONObject = null
  private var _xpub: Option[DeterministicKey] = None
}
