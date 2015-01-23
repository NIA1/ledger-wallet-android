/**
 *
 * Queryable
 * Ledger wallet
 *
 * Created by Pierre Pollastri on 23/01/15.
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
package com.ledger.ledgerwallet.base.model

import org.json.{JSONArray, JSONObject}

import scala.collection.mutable
import scala.reflect.ClassTag

class Queryable[T <: BaseModel](implicit T: ClassTag[T]) {

  val structure = Model.modelStructure

  def inflate(json: JSONObject): T = {
    val obj = create
    structure foreach { case (key, property) =>
      if (json.has(key)) {
        property match {
          case string: BaseModel#StringProperty => string.set(json.getString(key))
          case int: BaseModel#IntProperty => int.set(json.getInt(key))
        }
      }
    }
    obj
  }

  def inflate(json: JSONArray): Array[T] = {
    val array = T.newArray(json.length())
    array(0) = create
    array
  }

  def create = T.runtimeClass.newInstance().asInstanceOf[T]

  private object Model extends T {
     def modelStructure: mutable.Map[String, BaseModel#Property[AnyRef]] = structure
  }
}