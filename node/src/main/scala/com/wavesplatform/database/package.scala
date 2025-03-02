package com.wavesplatform

import java.io.File
import java.nio.ByteBuffer
import java.util.{Map => JMap}

import com.google.common.base.Charsets.UTF_8
import com.google.common.io.ByteStreams.{newDataInput, newDataOutput}
import com.google.common.io.{ByteArrayDataInput, ByteArrayDataOutput}
import com.google.common.primitives.{Ints, Longs, Shorts}
import com.wavesplatform.account.PublicKey
import com.wavesplatform.block.Block.BlockInfo
import com.wavesplatform.block.validation.Validators
import com.wavesplatform.block.{Block, BlockHeader}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.crypto._
import com.wavesplatform.lang.script.{Script, ScriptReader}
import com.wavesplatform.state._
import com.wavesplatform.transaction.{Transaction, TransactionParsers, TxValidationError}
import com.wavesplatform.utils.ScorexLogging
import org.iq80.leveldb.{DB, Options, ReadOptions}

package object database extends ScorexLogging {
  def openDB(path: String, recreate: Boolean = false): DB = {
    log.debug(s"Open DB at $path")
    val file = new File(path)
    val options = new Options()
      .createIfMissing(true)
      .paranoidChecks(true)

    if (recreate) {
      LevelDBFactory.factory.destroy(file, options)
    }

    file.getAbsoluteFile.getParentFile.mkdirs()
    LevelDBFactory.factory.open(file, options)
  }

  final type DBEntry = JMap.Entry[Array[Byte], Array[Byte]]

  implicit class ByteArrayDataOutputExt(val output: ByteArrayDataOutput) extends AnyVal {
    def writeByteStr(s: ByteStr): Unit = {
      output.write(s.arr)
    }

    def writeBigInt(v: BigInt): Unit = {
      val b = v.toByteArray
      require(b.length <= Byte.MaxValue)
      output.writeByte(b.length)
      output.write(b)
    }

    def writeScriptOption(v: Option[Script]): Unit = {
      output.writeBoolean(v.isDefined)
      v.foreach { s =>
        val b = s.bytes().arr
        output.writeShort(b.length)
        output.write(b)
      }
    }
  }

  implicit class ByteArrayDataInputExt(val input: ByteArrayDataInput) extends AnyVal {
    def readBigInt(): BigInt = {
      val len = input.readByte()
      val b   = new Array[Byte](len)
      input.readFully(b)
      BigInt(b)
    }

    def readScriptOption(): Option[Script] = {
      if (input.readBoolean()) {
        val len = input.readShort()
        val b   = new Array[Byte](len)
        input.readFully(b)
        Some(ScriptReader.fromBytes(b).explicitGet())
      } else None
    }

    def readBytes(len: Int): Array[Byte] = {
      val arr = new Array[Byte](len)
      input.readFully(arr)
      arr
    }

    def readByteStr(len: Int): ByteStr = {
      ByteStr(readBytes(len))
    }

    def readSignature: ByteStr   = readByteStr(SignatureLength)
    def readPublicKey: PublicKey = PublicKey(readBytes(KeyLength))
  }

  def writeIntSeq(values: Seq[Int]): Array[Byte] = {
    values.foldLeft(ByteBuffer.allocate(4 * values.length))(_ putInt _).array()
  }

  def readIntSeq(data: Array[Byte]): Seq[Int] = Option(data).fold(Seq.empty[Int]) { d =>
    val in = ByteBuffer.wrap(data)
    Seq.fill(d.length / 4)(in.getInt)
  }

  def readTxIds(data: Array[Byte]): List[ByteStr] = Option(data).fold(List.empty[ByteStr]) { d =>
    val b   = ByteBuffer.wrap(d)
    val ids = List.newBuilder[ByteStr]

    while (b.remaining() > 0) {
      val buffer = b.get() match {
        case crypto.DigestLength      => new Array[Byte](crypto.DigestLength)
        case crypto.SignatureLength => new Array[Byte](crypto.SignatureLength)
      }
      b.get(buffer)
      ids += ByteStr(buffer)
    }

    ids.result()
  }

  def writeTxIds(ids: Seq[ByteStr]): Array[Byte] =
    ids
      .foldLeft(ByteBuffer.allocate(ids.map(_.arr.length + 1).sum)) {
        case (b, id) =>
          b.put(id.arr.length match {
              case crypto.DigestLength      => crypto.DigestLength.toByte
              case crypto.SignatureLength => crypto.SignatureLength.toByte
            })
            .put(id.arr)
      }
      .array()

  def readStrings(data: Array[Byte]): Seq[String] = Option(data).fold(Seq.empty[String]) { _ =>
    var i = 0
    val s = Seq.newBuilder[String]

    while (i < data.length) {
      val len = Shorts.fromByteArray(data.drop(i))
      s += new String(data, i + 2, len, UTF_8)
      i += (2 + len)
    }
    s.result()
  }

  def writeStrings(strings: Seq[String]): Array[Byte] =
    strings
      .foldLeft(ByteBuffer.allocate(strings.map(_.getBytes(UTF_8).length + 2).sum)) {
        case (b, s) =>
          val bytes = s.getBytes(UTF_8)
          b.putShort(bytes.length.toShort).put(bytes)
      }
      .array()

  def writeBigIntSeq(values: Seq[BigInt]): Array[Byte] = {
    require(values.length <= Short.MaxValue, s"BigInt sequence is too long")
    val ndo = newDataOutput()
    ndo.writeShort(values.size)
    for (v <- values) {
      ndo.writeBigInt(v)
    }
    ndo.toByteArray
  }

  def readBigIntSeq(data: Array[Byte]): Seq[BigInt] = Option(data).fold(Seq.empty[BigInt]) { d =>
    val ndi    = newDataInput(d)
    val length = ndi.readShort()
    for (_ <- 0 until length) yield ndi.readBigInt()
  }

  def writeLeaseBalance(lb: LeaseBalance): Array[Byte] = {
    val ndo = newDataOutput()
    ndo.writeLong(lb.in)
    ndo.writeLong(lb.out)
    ndo.toByteArray
  }

  def readLeaseBalance(data: Array[Byte]): LeaseBalance = Option(data).fold(LeaseBalance.empty) { d =>
    val ndi = newDataInput(d)
    LeaseBalance(ndi.readLong(), ndi.readLong())
  }

  def readVolumeAndFee(data: Array[Byte]): VolumeAndFee = Option(data).fold(VolumeAndFee.empty) { d =>
    val ndi = newDataInput(d)
    VolumeAndFee(ndi.readLong(), ndi.readLong())
  }

  def writeVolumeAndFee(vf: VolumeAndFee): Array[Byte] = {
    val ndo = newDataOutput()
    ndo.writeLong(vf.volume)
    ndo.writeLong(vf.fee)
    ndo.toByteArray
  }

  def readTransactionInfo(data: Array[Byte]): (Int, Transaction) =
    (Ints.fromByteArray(data), TransactionParsers.parseBytes(data.drop(4)).get)

  def readTransactionHeight(data: Array[Byte]): Int = Ints.fromByteArray(data)

  def writeTransactionInfo(txInfo: (Int, Transaction)): Array[Byte] = {
    val (h, tx) = txInfo
    val txBytes = tx.bytes()
    ByteBuffer.allocate(4 + txBytes.length).putInt(h).put(txBytes).array()
  }

  def readTransactionIds(data: Array[Byte]): Seq[(Int, ByteStr)] = Option(data).fold(Seq.empty[(Int, ByteStr)]) { d =>
    val b   = ByteBuffer.wrap(d)
    val ids = Seq.newBuilder[(Int, ByteStr)]
    while (b.hasRemaining) {
      ids += b.get.toInt -> {
        val buf = new Array[Byte](b.get)
        b.get(buf)
        ByteStr(buf)
      }
    }
    ids.result()
  }

  def writeTransactionIds(ids: Seq[(Int, ByteStr)]): Array[Byte] = {
    val size   = ids.foldLeft(0) { case (prev, (_, id)) => prev + 2 + id.arr.length }
    val buffer = ByteBuffer.allocate(size)
    for ((typeId, id) <- ids) {
      buffer.put(typeId.toByte).put(id.arr.length.toByte).put(id.arr)
    }
    buffer.array()
  }

  def readFeatureMap(data: Array[Byte]): Map[Short, Int] = Option(data).fold(Map.empty[Short, Int]) { _ =>
    val b        = ByteBuffer.wrap(data)
    val features = Map.newBuilder[Short, Int]
    while (b.hasRemaining) {
      features += b.getShort -> b.getInt
    }

    features.result()
  }

  def writeFeatureMap(features: Map[Short, Int]): Array[Byte] = {
    val b = ByteBuffer.allocate(features.size * 6)
    for ((featureId, height) <- features)
      b.putShort(featureId).putInt(height)

    b.array()
  }

  def readSponsorship(data: Array[Byte]): SponsorshipValue = {
    val ndi = newDataInput(data)
    SponsorshipValue(ndi.readLong())
  }

  def writeSponsorship(ai: SponsorshipValue): Array[Byte] = {
    val ndo = newDataOutput()
    ndo.writeLong(ai.minFee)
    ndo.toByteArray
  }

  def readAssetInfo(data: Array[Byte]): AssetInfo = {
    val ndi     = newDataInput(data)
    val reissue = ndi.readBoolean()
    val volume  = ndi.readBigInt()
    AssetInfo(reissue, volume)
  }

  def writeAssetInfo(ai: AssetInfo): Array[Byte] = {
    val ndo = newDataOutput()
    ndo.writeBoolean(ai.isReissuable)
    ndo.writeBigInt(ai.volume)
    ndo.toByteArray
  }

  def writeBlockInfo(data: BlockInfo): Array[Byte] = {
    val BlockInfo(bh, size, transactionCount, signature) = data

    val ndo = newDataOutput()

    ndo.writeInt(size)

    ndo.writeByte(bh.version)
    ndo.writeLong(bh.timestamp)
    ndo.write(bh.reference)
    ndo.writeLong(bh.baseTarget)
    ndo.write(bh.generationSignature)

    if (bh.version == Block.GenesisBlockVersion | bh.version == Block.PlainBlockVersion)
      ndo.writeByte(transactionCount)
    else
      ndo.writeInt(transactionCount)

    ndo.writeInt(bh.featureVotes.size)
    bh.featureVotes.foreach(s => ndo.writeShort(s))

    if (bh.version > Block.NgBlockVersion)
      ndo.writeLong(bh.rewardVote)

    ndo.write(bh.generator)

    if (bh.version > Block.RewardBlockVersion) { // todo: (NODE-1972) Don't write length in case of using standard digest
      ndo.writeInt(bh.transactionsRoot.arr.length)
      ndo.writeByteStr(bh.transactionsRoot)
    }

    ndo.write(signature)

    ndo.toByteArray
  }

  def readBlockInfo(bs: Array[Byte]): BlockInfo = {
    val ndi = newDataInput(bs)

    val size   = ndi.readInt()
    val version   = ndi.readByte()
    val timestamp = ndi.readLong()

    val referenceArr = new Array[Byte](SignatureLength)
    ndi.readFully(referenceArr)

    val baseTarget = ndi.readLong()

    val genSigLength = if (version < Block.ProtoBlockVersion) Block.GenerationSignatureLength else Block.GenerationVRFSignatureLength
    val genSig = new Array[Byte](genSigLength)
    ndi.readFully(genSig)

    val transactionCount = {
      if (version == Block.GenesisBlockVersion || version == Block.PlainBlockVersion) ndi.readByte()
      else ndi.readInt()
    }

    val featureVotesCount = ndi.readInt()
    val featureVotes      = List.fill(featureVotesCount)(ndi.readShort())
    val rewardVote        = if (version > Block.NgBlockVersion) ndi.readLong() else -1L

    val generator = new Array[Byte](KeyLength)
    ndi.readFully(generator)

    val transactionsRoot = if (version < Block.ProtoBlockVersion) ByteStr.empty else ndi.readByteStr(ndi.readInt())

    val signature = new Array[Byte](SignatureLength)
    ndi.readFully(signature)

    val header =  BlockHeader(
      version,
      timestamp,
      ByteStr(referenceArr),
      baseTarget,
      ByteStr(genSig),
      PublicKey(ByteStr(generator)),
      featureVotes,
      rewardVote,
      transactionsRoot
    )

    BlockInfo(header, size, transactionCount, ByteStr(signature))
  }

  def readTransactionHNSeqAndType(bs: Array[Byte]): (Height, Seq[(Byte, TxNum)]) = {
    val ndi          = newDataInput(bs)
    val height       = Height(ndi.readInt())
    val numSeqLength = ndi.readInt()

    (height, List.fill(numSeqLength) {
      val tp  = ndi.readByte()
      val num = TxNum(ndi.readShort())
      (tp, num)
    })
  }

  def writeTransactionHNSeqAndType(v: (Height, Seq[(Byte, TxNum)])): Array[Byte] = {
    val (height, numSeq) = v
    val numSeqLength     = numSeq.length

    val outputLength = 4 + 4 + numSeqLength * (4 + 1)
    val ndo          = newDataOutput(outputLength)

    ndo.writeInt(height)
    ndo.writeInt(numSeqLength)
    numSeq.foreach {
      case (tp, num) =>
        ndo.writeByte(tp)
        ndo.writeShort(num)
    }

    ndo.toByteArray
  }

  def readTransactionHN(bs: Array[Byte]): (Height, TxNum) = {
    val ndi = newDataInput(bs)
    val h   = Height(ndi.readInt())
    val num = TxNum(ndi.readShort())

    (h, num)
  }

  def writeTransactionHN(v: (Height, TxNum)): Array[Byte] = {
    val ndo = newDataOutput(8)

    val (h, num) = v

    ndo.writeInt(h)
    ndo.writeShort(num)

    ndo.toByteArray
  }

  implicit class EntryExt(val e: JMap.Entry[Array[Byte], Array[Byte]]) extends AnyVal {
    import com.wavesplatform.crypto.DigestLength
    def extractId(offset: Int = 2, length: Int = DigestLength): ByteStr = {
      val id = ByteStr(new Array[Byte](length))
      Array.copy(e.getKey, offset, id.arr, 0, length)
      id
    }
  }

  implicit class DBExt(val db: DB) extends AnyVal {
    def readOnly[A](f: ReadOnlyDB => A): A = {
      val snapshot = db.getSnapshot
      try f(new ReadOnlyDB(db, new ReadOptions().snapshot(snapshot)))
      finally snapshot.close()
    }

    /**
      * @note Runs operations in batch, so keep in mind, that previous changes don't appear lately in f
      */
    def readWrite[A](f: RW => A): A = {
      val snapshot    = db.getSnapshot
      val readOptions = new ReadOptions().snapshot(snapshot)
      val batch       = db.createWriteBatch()
      val rw          = new RW(db, readOptions, batch)
      try {
        val r = f(rw)
        db.write(batch)
        r
      } finally {
        batch.close()
        snapshot.close()
      }
    }

    def get[A](key: Key[A]): A    = key.parse(db.get(key.keyBytes))
    def has(key: Key[_]): Boolean = db.get(key.keyBytes) != null

    def iterateOver(prefix: Short)(f: DBEntry => Unit): Unit =
      iterateOver(Shorts.toByteArray(prefix))(f)

    def iterateOver(prefix: Array[Byte])(f: DBEntry => Unit): Unit = {
      val iterator = db.iterator()
      try {
        iterator.seek(prefix)
        while (iterator.hasNext && iterator.peekNext().getKey.startsWith(prefix)) f(iterator.next())
      } finally iterator.close()
    }
  }

  def createBlock(header: BlockHeader, signature: ByteStr, txs: Seq[Transaction]): Either[TxValidationError.GenericError, Block] =
    Validators.validateBlock(Block(header, signature, txs))

  def writeScript(script: (Script, Long)): Array[Byte] =
    script._1.bytes().arr ++ Longs.toByteArray(script._2)

  def readScript(b: Array[Byte]): (Script, Long) =
    (
      ScriptReader.fromBytes(b.dropRight(8)).explicitGet(),
      ByteBuffer.wrap(b, b.length - 8, 8).getLong
    )
}
