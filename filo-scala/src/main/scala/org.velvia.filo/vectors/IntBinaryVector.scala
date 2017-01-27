package org.velvia.filo.vectors

import java.nio.ByteBuffer
import org.velvia.filo._
import scala.language.postfixOps
import scalaxy.loops._

object IntBinaryVector {
  /**
   * Creates a new MaskedIntAppendingVector, allocating a byte array of the right size for the max #
   * of elements.
   * @param maxElements maximum number of elements this vector will hold.  If more are appended then
   *                    an exception will be thrown.
   */
  def appendingVector(maxElements: Int,
                      nbits: Short = 32,
                      signed: Boolean = true): MaskedIntAppendingVector = {
    val bytesRequired = 4 + BitmapMask.numBytesRequired(maxElements) + 4 + (maxElements * nbits / 8)
    val (base, off, nBytes) = BinaryVector.allocWithMagicHeader(bytesRequired)
    new MaskedIntAppendingVector(base, off, nBytes, maxElements, nbits, signed)
  }

  /**
   * Same as appendingVector but uses a SimpleAppendingVector with no ability to hold NA mask
   */
  def appendingVectorNoNA(maxElements: Int,
                          nbits: Short = 32,
                          signed: Boolean = true): IntAppendingVector = {
    val bytesRequired = 4 + (maxElements * nbits / 8)
    val (base, off, nBytes) = BinaryVector.allocWithMagicHeader(bytesRequired)
    appendingVectorNoNA(base, off, nBytes, nbits, signed)
  }

  def appendingVectorNoNA(base: Any,
                          offset: Long,
                          maxBytes: Int,
                          nbits: Short,
                          signed: Boolean): IntAppendingVector = nbits match {
    case 32 => new IntAppendingVector(base, offset, maxBytes, nbits, signed) {
      final def addValue(v: Int): Unit = {
        UnsafeUtils.setInt(base, offset + numBytes, v)
        numBytes += 4
      }
    }
    case 16 => new IntAppendingVector(base, offset, maxBytes, nbits, signed) {
      final def addValue(v: Int): Unit = {
        UnsafeUtils.setShort(base, offset + numBytes, v.toShort)
        numBytes += 2
      }
    }
    case 8 => new IntAppendingVector(base, offset, maxBytes, nbits, signed) {
      final def addValue(v: Int): Unit = {
        UnsafeUtils.setByte(base, offset + numBytes, v.toByte)
        numBytes += 1
      }
    }
  }

  /**
   * Creates a BinaryVector[Int] with no NAMask
   */
  def apply(base: Any, offset: Long, numBytes: Int): BinaryVector[Int] = {
    val nbits = UnsafeUtils.getShort(base, offset)
    // offset+2: nonzero = signed integral vector
    if (UnsafeUtils.getByte(base, offset + 2) != 0) {
      nbits match {
        case 32 => new IntBinaryVector(base, offset, numBytes, nbits) {
          final def apply(index: Int): Int = UnsafeUtils.getInt(base, bufOffset + index * 4)
        }
        case 16 => new IntBinaryVector(base, offset, numBytes, nbits) {
          final def apply(index: Int): Int = UnsafeUtils.getShort(base, bufOffset + index * 2).toInt
        }
        case 8 => new IntBinaryVector(base, offset, numBytes, nbits) {
          final def apply(index: Int): Int = UnsafeUtils.getByte(base, bufOffset + index).toInt
        }
      }
    } else {
      nbits match {
        case 32 => new IntBinaryVector(base, offset, numBytes, nbits) {
          final def apply(index: Int): Int = UnsafeUtils.getInt(base, bufOffset + index * 4)
        }
        case 16 => new IntBinaryVector(base, offset, numBytes, nbits) {
          final def apply(index: Int): Int = (UnsafeUtils.getShort(base, bufOffset + index * 2) & 0x0ffff).toInt
        }
        case 8 => new IntBinaryVector(base, offset, numBytes, nbits) {
          final def apply(index: Int): Int = (UnsafeUtils.getByte(base, bufOffset + index) & 0x00ff).toInt
        }
      }
    }
  }

  def masked(base: Any, offset: Long, numBytes: Int): MaskedIntBinaryVector =
    new MaskedIntBinaryVector(base, offset, numBytes)

  /**
   * Given the min and max values in an IntVector, determines the most optimal (smallest)
   * nbits and the signed flag to use.  Typically used in a workflow where you use
   * `IntBinaryVector.appendingVector` first, then further optimize to the smallest IntVector
   * available.
   */
  def minMaxToNbitsSigned(min: Int, max: Int): (Short, Boolean) = {
    // TODO: Add support for stuff below byte level
    if (min >= Byte.MinValue && max <= Byte.MaxValue) {
      (8, true)
    } else if (min >= 0 && max < 256) {
      (8, false)
    } else if (min >= Short.MinValue && max <= Short.MaxValue) {
      (16, true)
    } else if (min >= 0 && max < 65536) {
      (16, false)
    } else {
      (32, true)
    }
  }

  /**
   * Produces a smaller BinaryVector if possible given combination of minimal nbits as well as
   * if all values are not NA.
   * The output is a BinaryAppendableVector with optimized nbits and without mask if appropriate,
   * but not frozen.  You need to call freeze / toFiloBuffer yourself.
   */
  def optimize(vector: MaskedIntAppendingVector): BinaryAppendableVector[Int] = {
    // Get nbits and signed
    val (min, max) = vector.minMax
    val (nbits, signed) = minMaxToNbitsSigned(min, max)

    // No NAs?  Use just the PrimitiveAppendableVector
    if (vector.noNAs) {
      if (nbits == vector.nbits) { vector.intVect }
      else {
        val newVect = IntBinaryVector.appendingVectorNoNA(vector.length, nbits, signed)
        newVect.addVector(vector)
        newVect
      }
    } else {
      // Some NAs and same number of bits?  Just keep NA mask
      if (nbits == vector.nbits) { vector }
      // Some NAs and different number of bits?  Create new vector and copy data over
      else {
        val newVect = IntBinaryVector.appendingVector(vector.length, nbits, signed)
        newVect.addVector(vector)
        newVect
      }
    }
  }
}

abstract class IntBinaryVector(val base: Any,
                               val offset: Long,
                               val numBytes: Int,
                               nbits: Short) extends BinaryVector[Int] {
  final val bufOffset = offset + 4
  // This length method works assuming nbits is divisible into 32
  override def length: Int = (numBytes - 4) * 8 / nbits
  final def isAvailable(index: Int): Boolean = true
}

class MaskedIntBinaryVector(val base: Any, val offset: Long, val numBytes: Int) extends BitmapMaskVector[Int] {
  // First four bytes: offset to SimpleIntBinaryVector
  val bitmapOffset = offset + 4L
  val intVectOffset = UnsafeUtils.getInt(base, offset)
  private val intVect = IntBinaryVector(base, offset + intVectOffset, numBytes - intVectOffset)

  override final def length: Int = intVect.length
  final def apply(index: Int): Int = intVect.apply(index)
}

abstract class IntAppendingVector(base: Any,
                                  offset: Long,
                                  maxBytes: Int,
                                  nbits: Short,
                                  signed: Boolean)
extends PrimitiveAppendableVector[Int](base, offset, maxBytes, nbits, signed) {
  final def addNA(): Unit = addData(0)

  private final val readVect = IntBinaryVector(base, offset, maxBytes)
  final def apply(index: Int): Int = readVect.apply(index)
  final def isAvailable(index: Int): Boolean = true


  override final def addVector(other: BinaryVector[Int]): Unit = other match {
    case v: MaskedIntAppendingVector =>
      addVector(v.intVect)
    case v: BinaryVector[Int] =>
      // Optimization: this vector does not support NAs so just add the data
      assert(numBytes + (nbits * v.length / 8) <= maxBytes,
             s"Not enough space to add ${v.length} elems; nbits=$nbits; need ${maxBytes-numBytes} bytes")
      for { i <- 0 until v.length optimized } {
        addValue(v(i))
      }
      _len += other.length
  }
}

class MaskedIntAppendingVector(base: Any,
                               val offset: Long,
                               val maxBytes: Int,
                               maxElements: Int,
                               val nbits: Short,
                               signed: Boolean) extends
// First four bytes: offset to SimpleIntBinaryVector
BitmapMaskAppendableVector[Int](base, offset + 4L, maxElements) {
  val vectMajorType = WireFormat.VECTORTYPE_BINSIMPLE
  val vectSubType = WireFormat.SUBTYPE_PRIMITIVE

  val intVectOffset = 4 + bitmapMaskBufferSize
  UnsafeUtils.setInt(base, offset, intVectOffset)
  val intVect = IntBinaryVector.appendingVectorNoNA(base, offset + intVectOffset,
                                                    maxBytes - intVectOffset,
                                                    nbits, signed)

  override final def length: Int = intVect.length
  final def numBytes: Int = 4 + bitmapMaskBufferSize + intVect.numBytes
  final def apply(index: Int): Int = intVect.apply(index)
  final def addEmptyValue(): Unit = intVect.addNA()
  final def addDataValue(data: Int): Unit = intVect.addData(data)

  final def minMax: (Int, Int) = {
    var min = Int.MaxValue
    var max = Int.MinValue
    for { index <- 0 until length optimized } {
      if (isAvailable(index)) {
        val data = intVect.apply(index)
        if (data < min) min = data
        if (data > max) max = data
      }
    }
    (min, max)
  }

  override final def addVector(other: BinaryVector[Int]): Unit = other match {
    // Optimized case: we are empty, so just copy over entire bitmap from other one
    case v: MaskedIntAppendingVector if length == 0 =>
      copyMaskFrom(v)
      intVect.addVector(v.intVect)
    // Non-optimized  :(
    case v: BinaryVector[Int] =>
      super.addVector(other)
  }

  override def freeze(): BinaryVector[Int] = {
    // If bitmap bytes is already same as allocated size, then nothing needs to be done
    if (bitmapBytes == bitmapMaskBufferSize) { this.asInstanceOf[BinaryVector[Int]] }
    // Otherwise move the next element back
    else {
      copyTo(base, bitmapOffset + bitmapBytes, intVectOffset, intVect.numBytes)
      // Don't forget to write the new intVectOffset
      UnsafeUtils.setInt(base, offset, (bitmapOffset + bitmapBytes - offset).toInt)
      new MaskedIntBinaryVector(base, offset, 4 + bitmapBytes + intVect.numBytes)
    }
  }
}