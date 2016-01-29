/**
 *
 * WalletService
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
package co.ledger.wallet.service.wallet

import java.util.concurrent.ConcurrentHashMap

import android.app.Service
import android.content.{Context, Intent}
import android.os.IBinder
import co.ledger.wallet.core.utils.Preferences
import co.ledger.wallet.service.wallet.spv.SpvWalletClient
import co.ledger.wallet.wallet.Wallet
import org.bitcoinj.params.MainNetParams

import scala.collection.JavaConverters._

class WalletService extends Service {
  import WalletService._

  def wallet(name: String, engine: Int): Wallet = {
    _wallets.lift(name) getOrElse {
      val wallet: Wallet = engine match {
        case WalletService.WalletSpvEngine =>
          new SpvWalletClient(this, s"wallet_$name", networkParams)
        case WalletService.WalletApiEngine =>
          null
      }
      _wallets(name) = wallet
      wallet
    }
  }

  def networkParams = {
    // TODO: Proper implementation
    MainNetParams.get()
  }

  def openWallet(name: String): Wallet = {
    _preferences.writer.putString(CurrentWalletNameKey, name).commit()
    wallet(name, defaultEngineFlag)
  }

  def defaultEngineFlag = WalletService.WalletSpvEngine
  def currentWalletName: Option[String] =
    Option(_preferences.reader.getString(CurrentWalletNameKey, null))

  def currentWallet: Option[Wallet] = currentWalletName map openWallet

  def closeCurrentWallet(): Unit = currentWallet foreach {(wallet) =>
    _preferences.writer.remove(CurrentWalletNameKey).commit()
    // TODO: Close
  }

  override def onCreate(): Unit = {
    super.onCreate()
    this.startService(new Intent(this, this.getClass))
  }

  override def onBind(intent: Intent): IBinder = _binder

  class Binder extends android.os.Binder {
    def service = WalletService.this
  }

  private[this] val _binder = new Binder
  private[this] val _wallets = new ConcurrentHashMap[String, Wallet]().asScala
  private[this] lazy val _preferences = Preferences(WalletServicePreferencesName)(this)
}

object WalletService {

  val WalletServicePreferencesName = "WalletServicePreferences"
  val CurrentWalletNameKey = "current_wallet_name"

  val WalletSpvEngine = 0x01
  val WalletApiEngine = 0x02

  def currentWalletName(implicit context: Context): Option[String] = {
    val preferences = Preferences(WalletServicePreferencesName)(context)
    Option(preferences.reader.getString(CurrentWalletNameKey, null))
  }

  def setCurrentWalletName(walletIdentifier: String)(implicit context: Context): Unit = {
    val preferences = Preferences(WalletServicePreferencesName)(context)
    preferences.writer.putString(CurrentWalletNameKey, walletIdentifier).commit()
  }

  def closeCurrentWallet()(implicit context: Context): Unit = {
    val preferences = Preferences(WalletServicePreferencesName)(context)
    preferences.writer.remove(CurrentWalletNameKey).commit()
  }

}