package io.mosip.tuvali.verifier.transfer

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import io.mosip.tuvali.ble.peripheral.Peripheral
import io.mosip.tuvali.transfer.Assembler
import io.mosip.tuvali.transfer.Chunker
import io.mosip.tuvali.transfer.Semaphore
import io.mosip.tuvali.verifier.GattService
import io.mosip.tuvali.verifier.exception.CorruptedChunkReceivedException
import io.mosip.tuvali.verifier.transfer.message.*
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class TransferHandler(looper: Looper, private val peripheral: Peripheral, private val transferListener: ITransferListener, val serviceUUID: UUID) : Handler(looper) {
  private val logTag = "TransferHandler"
  enum class States {
    UnInitialised,
    RequestSizeWritePending,
    RequestSizeWriteSuccess,
    RequestSizeWriteFailed,
    RequestWritePending,
    RequestWriteFailed,
    ResponseSizeReadPending,
    ResponseReadPending,
    ResponseReadFailed,
    TransferComplete
  }

  private var currentState: States = States.UnInitialised
  private var requestData: ByteArray = byteArrayOf()
  private var chunker: Chunker? = null
  private var assembler: Assembler? = null
  private var semaphoreWriteAtomic: AtomicInteger = AtomicInteger(Semaphore.SemaphoreMarker.UnInitialised.ordinal)
  private var responseStartTimeInMillis: Long = 0

  override fun handleMessage(msg: Message) {
    when(msg.what) {
      IMessage.TransferMessageTypes.INIT_REQUEST_TRANSFER.ordinal -> {
        val initTransferMessage = msg.obj as InitTransferMessage
        requestData = initTransferMessage.data
        chunker = Chunker(requestData)
        currentState = States.RequestSizeWritePending
        this.sendMessage(RequestSizeWritePendingMessage(requestData.size))
      }
      IMessage.TransferMessageTypes.REQUEST_SIZE_WRITE_PENDING.ordinal -> {
        val requestSizeWritePendingMessage = msg.obj as RequestSizeWritePendingMessage
        sendRequestSize(requestSizeWritePendingMessage.size)
      }
      IMessage.TransferMessageTypes.REQUEST_SIZE_WRITE_SUCCESS.ordinal -> {
        Log.d(logTag, "handleMessage: request size write success")
        currentState = States.RequestSizeWriteSuccess
        initRequestChunkSend()
      }
      IMessage.TransferMessageTypes.REQUEST_SIZE_WRITE_FAILED.ordinal -> {
        val requestSizeWriteFailedMessage = msg.obj as RequestSizeWriteFailedMessage
        Log.e(logTag, "handleMessage: request size write failed with error: ${requestSizeWriteFailedMessage.errorMsg}")
        currentState = States.RequestSizeWriteFailed
      }
      IMessage.TransferMessageTypes.INIT_REQUEST_CHUNK_TRANSFER.ordinal -> {
        sendRequestChunk()
        currentState = States.RequestWritePending
      }
      IMessage.TransferMessageTypes.UPDATE_CHUNK_WROTE_STATUS_TO_REMOTE.ordinal -> {
        val updateChunkWroteStatusToRemoteMessage = msg.obj as UpdateChunkWroteStatusToRemoteMessage
        when(updateChunkWroteStatusToRemoteMessage.semaphoreCharValue) {
          Semaphore.SemaphoreMarker.ProcessChunkPending.ordinal -> {
            markChunkSend()
          }
        }
      }
      IMessage.TransferMessageTypes.CHUNK_READ_BY_REMOTE_STATUS_UPDATED.ordinal -> {
        val chunkReadByRemoteStatusUpdatedMessage = msg.obj as ChunkReadByRemoteStatusUpdatedMessage
        when(chunkReadByRemoteStatusUpdatedMessage.semaphoreCharValue) {
          Semaphore.SemaphoreMarker.ProcessChunkComplete.ordinal -> {
            this.sendMessage(RequestChunkWriteSuccessMessage())
          }
          Semaphore.SemaphoreMarker.ResendChunk.ordinal -> {
            Log.d(logTag, "handleMessage: resend chunk requested")
          }
          Semaphore.SemaphoreMarker.Error.ordinal -> {
            Log.d(logTag, "handleMessage: chunk marked as error while reading by remote")
          }
        }
      }
      IMessage.TransferMessageTypes.CHUNK_WROTE_BY_REMOTE_STATUS_UPDATED.ordinal -> {
        val chunkWroteByRemoteStatusUpdatedMessage = msg.obj as ChunkWroteByRemoteStatusUpdatedMessage
        when(chunkWroteByRemoteStatusUpdatedMessage.semaphoreCharValue) {
          Semaphore.SemaphoreMarker.ProcessChunkPending.ordinal -> {
            val oldState = semaphoreWriteAtomic.getAndSet(Semaphore.SemaphoreMarker.ProcessChunkPending.ordinal)
            Log.d(logTag, "chunk wrote by remote status updated from old value: $oldState to ${Semaphore.SemaphoreMarker.ProcessChunkPending.ordinal}")
          }
        }
      }
      IMessage.TransferMessageTypes.REQUEST_CHUNK_WRITE_SUCCESS.ordinal -> {
        sendRequestChunk()
      }
      IMessage.TransferMessageTypes.REQUEST_CHUNK_WRITE_FAILED.ordinal -> {
        val requestChunkWriteFailedMessage = msg.obj as RequestChunkWriteFailedMessage
        Log.e(logTag, "request chunk write to remote failed: ${requestChunkWriteFailedMessage.errorMsg}")
        currentState = States.RequestWriteFailed
      }
      IMessage.TransferMessageTypes.REQUEST_TRANSFER_COMPLETE.ordinal -> {
        markChunkTransferComplete()
        currentState = States.ResponseSizeReadPending
      }
      IMessage.TransferMessageTypes.RESPONSE_SIZE_READ.ordinal -> {
        responseStartTimeInMillis = System.currentTimeMillis()
        val responseSizeReadSuccessMessage = msg.obj as ResponseSizeReadSuccessMessage
        try {
          assembler = Assembler(responseSizeReadSuccessMessage.responseSize)
        } catch (c: CorruptedChunkReceivedException) {
          this.sendMessage(ResponseTransferFailedMessage("Corrupted Data from Remote " + c.message.toString()))
          return
        }
        currentState = States.ResponseReadPending
      }
      // On verifier side, we can wait on response char, instead of semaphore to know when chunk arrived
      IMessage.TransferMessageTypes.RESPONSE_CHUNK_RECEIVED.ordinal -> {
        val responseChunkReceivedMessage = msg.obj as ResponseChunkReceivedMessage
        assembleChunk(responseChunkReceivedMessage.chunkData)
      }
      IMessage.TransferMessageTypes.UPDATE_CHUNK_RECEIVED_STATUS_TO_REMOTE.ordinal -> {
        val updateChunkReceivedStatusToRemoteMessage =
          msg.obj as UpdateChunkReceivedStatusToRemoteMessage
        // Mark it as completed only if the previous state was pending
        if (semaphoreWriteAtomic.get() == Semaphore.SemaphoreMarker.ProcessChunkPending.ordinal) {
          when(updateChunkReceivedStatusToRemoteMessage.semaphoreCharValue) {
            Semaphore.SemaphoreMarker.ProcessChunkComplete.ordinal -> markChunkReceive()
            Semaphore.SemaphoreMarker.ResendChunk.ordinal -> Log.e(logTag, "receive semaphore value to re-read")
          }
        } else {
          this.sendMessageDelayed(updateChunkReceivedStatusToRemoteMessage, 2)
        }
      }
      IMessage.TransferMessageTypes.RESPONSE_TRANSFER_COMPLETE.ordinal -> {
        Log.d(logTag, "response transfer complete in ${System.currentTimeMillis() - responseStartTimeInMillis}ms")
        val responseTransferCompleteMessage = msg.obj as ResponseTransferCompleteMessage
        transferListener.onResponseReceived(responseTransferCompleteMessage.data)
        currentState = States.TransferComplete
      }
      IMessage.TransferMessageTypes.RESPONSE_TRANSFER_FAILED.ordinal -> {
        val responseTransferFailedMessage = msg.obj as ResponseTransferFailedMessage
        Log.d(logTag, "handleMessage: response transfer failed")
        transferListener.onResponseReceivedFailed(responseTransferFailedMessage.errorMsg)
        currentState = States.ResponseReadFailed
      }
    }
  }

  private fun markChunkSend() {
    peripheral.sendData(
      serviceUUID,
      GattService.SEMAPHORE_CHAR_UUID,
      byteArrayOf(Semaphore.SemaphoreMarker.ProcessChunkPending.ordinal.toByte())
    )
  }

  private fun markChunkReceive() {
    peripheral.sendData(
      serviceUUID,
      GattService.SEMAPHORE_CHAR_UUID,
      byteArrayOf(Semaphore.SemaphoreMarker.ProcessChunkComplete.ordinal.toByte())
    )
    // TODO: Can update this value once above write call is success - UpdateChunkWroteStatusToRemoteMessage
    semaphoreWriteAtomic.getAndSet(Semaphore.SemaphoreMarker.ProcessChunkComplete.ordinal)
  }

  private fun markChunkTransferComplete() {
    peripheral.sendData(
      serviceUUID,
      GattService.SEMAPHORE_CHAR_UUID,
      byteArrayOf(Semaphore.SemaphoreMarker.UnInitialised.ordinal.toByte())
    )
  }

  private fun sendRequestChunk() {
    if (chunker?.isComplete() == true) {
      this.sendMessage(RequestTransferCompleteMessage())
      return
    }
    val chunkArray = chunker?.next()
    if (chunkArray != null) {
      peripheral.sendData(
        serviceUUID,
        GattService.REQUEST_CHAR_UUID,
        chunkArray
      )
      this.sendMessage(UpdateChunkWroteStatusToRemoteMessage(Semaphore.SemaphoreMarker.ProcessChunkPending.ordinal))
    }
  }

  private fun assembleChunk(chunkData: ByteArray) {
    if (assembler?.isComplete() == true) {
      return
    }
    assembler?.addChunk(chunkData)
    this.sendMessage(UpdateChunkReceivedStatusToRemoteMessage(Semaphore.SemaphoreMarker.ProcessChunkComplete.ordinal))

    if (assembler?.isComplete() == true) {
      if (assembler?.data() == null){
        return this.sendMessage(ResponseTransferFailedMessage("assembler is complete data is null"))
      }
      this.sendMessage(ResponseTransferCompleteMessage(assembler?.data()!!))
    }
  }

  private fun initRequestChunkSend() {
    val initRequestChunkTransferMessage =
      InitRequestChunkTransferMessage()
    this.sendMessage(initRequestChunkTransferMessage)
  }

  private fun sendRequestSize(size: Int) {
    peripheral.sendData(
      serviceUUID,
      GattService.REQUEST_SIZE_CHAR_UUID,
      arrayOf(size.toByte()).toByteArray()
    )
  }

  fun sendMessage(msg: IMessage) {
    val message = this.obtainMessage()
    message.what = msg.msgType.ordinal
    message.obj = msg
    this.sendMessage(message)
  }

  fun sendMessageDelayed(msg: IMessage, delayInMillis: Long) {
    val message = this.obtainMessage()
    message.what = msg.msgType.ordinal
    message.obj = msg
    this.sendMessageDelayed(message,delayInMillis)
  }

  fun getCurrentState(): States {
    return currentState
  }
}
