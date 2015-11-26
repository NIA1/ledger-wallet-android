/**
 *
 * SpvWalletClient
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
package co.ledger.wallet.service.wallet.spv

import java.io._
import java.util

import android.content.Context
import co.ledger.wallet.core.concurrent.{SerialQueueTask, ThreadPoolTask}
import co.ledger.wallet.core.utils.io.IOUtils
import co.ledger.wallet.core.utils.logs.Logger
import co.ledger.wallet.wallet.events.PeerGroupEvents.BlockDownloaded
import co.ledger.wallet.wallet.events.WalletEvents
import co.ledger.wallet.wallet.{Account, Wallet}
import de.greenrobot.event.EventBus
import org.bitcoinj.core._
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.store.{SPVBlockStore, MemoryBlockStore}
import org.json.JSONObject
import scala.concurrent.{Promise, Future}
import scala.collection.JavaConverters._
import WalletEvents._

import scala.util.{Failure, Success, Try}

class SpvWalletClient(val context: Context, val name: String, val networkParameters: NetworkParameters)
  extends Wallet with SerialQueueTask {

  type JWallet = org.bitcoinj.core.Wallet
  type JSpvBlockchain = org.bitcoinj.core.BlockChain
  type JPeerGroup = PeerGroup

  def AccountCountKey = "account_count"
  def AccountKey(index: Int) = s"account_$index"

  override def synchronize(): Future[Unit] = ???

  override def accounts(): Future[Array[Account]] = init().map({ (_) =>
    _accounts.asInstanceOf[Array[Account]]
  })

  override def account(index: Int): Account = {
    if (index >= _accounts.length) {
      for (i <- _accounts.length to index) {
        _accounts = _accounts :+ createSpvAccountInstance(i)
      }
    }
    _accounts(index)
  }

  override def transactions(): Future[Set[Transaction]] = ???
  override def balance(): Future[Coin] = {
    init().flatMap({ (_) =>
      val promise = Promise[Coin]()
      var sum = Coin.ZERO
      def iter(index: Int): Unit = {
        if (index >= _accounts.length) {
          promise.success(sum)
          return
        }
        _accounts(index).balance().onComplete {
          case Success(balance) =>
            sum = sum add balance
            iter(index + 1)
          case Failure(ex) => promise.failure(ex)
        }
      }
      iter(0)
      promise.future
    })
  }
  override def accountsCount(): Future[Int] = init().map({(_) => _accounts.length})

  def notifyEmptyAccountIsUsed(): Unit = {
    if (_peerGroup.isEmpty)
      throw new IllegalStateException("Wallet is not initialized")
    val index = _persistentState.get.optInt(AccountCountKey, 0)
    _accounts = _accounts :+ createSpvAccountInstance(index)
    _persistentState.get.put(AccountCountKey, _accounts.length)
    save()
  }

  def accountPersistedJson(index: Int): Future[JSONObject] = {
    init().map({ (_) =>
      var accountJson = _persistentState.get.optJSONObject(AccountKey(index))
      if (accountJson == null) {
        accountJson = new JSONObject()
        _persistentState.get.put(AccountKey(index), accountJson)
      }
      accountJson
    })
  }

  val eventBus =  EventBus
    .builder()
    .throwSubscriberException(true)
    .sendNoSubscriberEvent(false)
    .build()

  def peerGroup(): Future[JPeerGroup] = {
    init().map({ (_) => _peerGroup.get})
  }

  private def init(): Future[Unit] = Future {
    if (_peerGroup != null) {

      // Load the wallet from file
      if (_walletFile.exists()) {
        val writer = new StringWriter()
        val reader = new FileReader(_walletFile)
        IOUtils.copy(reader, writer)
        _persistentState = Try(new JSONObject(writer.toString)).toOption
      }
      _persistentState = _persistentState.orElse(Some(new JSONObject()))

      val persistentState = _persistentState.get
      val accountCount = persistentState.optInt(AccountCountKey, 0)

      if (_accounts.length < accountCount) {
        for (index <- _accounts.length until accountCount) {
          _accounts = _accounts :+ createSpvAccountInstance(index)
        }
      }

      // Create the peer group
      _peerGroup = Option(new JPeerGroup(networkParameters, blockChain))
      _peerGroup.get.setDownloadTxDependencies(false)
      _peerGroup.get.addPeerDiscovery(new DnsDiscovery(networkParameters))
      _peerGroup.get.startAsync()

      if (_accounts.length == 0)
        notifyEmptyAccountIsUsed()
    }
  }

  private def createSpvAccountInstance(index: Int): SpvAccountClient = {
    new SpvAccountClient(this, index)
  }

  def save(): Future[Unit] = Future {
    if (_persistentState.isEmpty) {
      throw new IllegalStateException("Error during save: client is not initialized")
    }
    val input = new StringReader(_persistentState.get.toString)
    IOUtils.copy(input, _walletFile)
  }

  /**
   * Temporary implementation
   * @return
   */
  /*
  private[this] def load(): Future[JWallet] = {
    val promise = Promise[JWallet]()
    Future {
      if (_wallet == null) {
        val params = MainNetParams.get()
        val key = DeterministicKey.deserializeB58("xpub6D4waFVPfPCpRvPkQd9A6n65z3hTp6TvkjnBHG5j2MCKytMuadKgfTUHqwRH77GQqCKTTsUXSZzGYxMGpWpJBdYAYVH75x7yMnwJvra1BUJ", params)

        _wallet = org.bitcoinj.core.Wallet.fromWatchingKey(params, key)
        val file = new File(context.getDir("toto", Context.MODE_PRIVATE), "blockstore")
        if (file.exists())
          file.delete()

        _spvBlockChain = new JSpvBlockchain(params, _wallet, new SPVBlockStore(params, file))
        _peerGroup = new JPeerGroup(params, _spvBlockChain)
        _peerGroup.setDownloadTxDependencies(false)
        _peerGroup.addWallet(_wallet)
        _peerGroup.addPeerDiscovery(new DnsDiscovery(params))
        _wallet.addEventListener(new AbstractWalletEventListener {
          override def onCoinsReceived(wallet: JWallet, tx: Transaction, prevBalance: Coin,
                                       newBalance: Coin): Unit = {
            super.onCoinsReceived(wallet, tx, prevBalance, newBalance)
            eventBus.post(TransactionReceived(tx))
          }
        })
        _peerGroup.startAsync()
        _peerGroup.startBlockChainDownload(new AbstractPeerEventListener() {


          override def onBlocksDownloaded(peer: Peer, block: Block, filteredBlock: FilteredBlock,
                                          blocksLeft: Int): Unit = {
            super.onBlocksDownloaded(peer, block, filteredBlock, blocksLeft)
            eventBus.post(BlockDownloaded(blocksLeft))
          }


          override def onPeerConnected(peer: Peer, peerCount: Int): Unit = {
            super.onPeerConnected(peer, peerCount)
            eventBus.post(s"Connected to peer: ${peer.getAddress.getAddr.toString} ${peerCount}")
          }

          override def onChainDownloadStarted(peer: Peer, blocksLeft: Int): Unit = {
            super.onChainDownloadStarted(peer, blocksLeft)
            eventBus.post("Start download blockchain")
          }

          override def onTransaction(peer: Peer, t: Transaction): Unit = {
            super.onTransaction(peer, t)
            eventBus.post(s"Received transaction (O_o) ${t.getHash.toString}")
          }
        })
      }
      promise.success(_wallet)
    } recover {
      case ex => eventBus.post(ex.getMessage)
    }
    promise.future
  } */

  private var _accounts = Array[SpvAccountClient]()
  private var _persistentState: Option[JSONObject] = None
  private var _peerGroup: Option[JPeerGroup] = None
  private def _walletFileName =
    s"${name}_${networkParameters.getAddressHeader}_${networkParameters.getP2SHHeader}"
  private lazy val _walletFile =
    new File(context.getDir("spv_wallets", Context.MODE_PRIVATE), _walletFileName)

  lazy val blockStoreFile =
    new File(context.getDir("blockstores", Context.MODE_PRIVATE), _walletFileName)
  lazy val blockStore = new SPVBlockStore(networkParameters, blockStoreFile)
  lazy val blockChain = new JSpvBlockchain(networkParameters, blockStore)
}

object SpvWalletClient {

  def peerGroup(implicit context: Context): PeerGroup = {
    _peerGroup
  }

  // Temporary
  private val _peerGroup = {
    val p = new PeerGroup(MainNetParams.get())
    p.start()

    p
  }

}
