package org.operatorfoundation.transmission

import java.lang.Exception
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.logging.Level
import java.util.logging.Logger

class TransmissionConnection(logger: Logger?) : BaseConnection(logger)
{
    var udpConnection: DatagramSocket? = null
    var tcpConnection: Socket? = null

    constructor(host:String, port: Int, type: ConnectionType = ConnectionType.TCP, logger: Logger?) : this(logger)
    {
        when (type)
        {
            ConnectionType.TCP ->
            {
                try
                {
                    val socketAddress = InetSocketAddress(host, port)
                    this.tcpConnection = Socket()
                    this.tcpConnection!!.connect(socketAddress)
                    this.connectionClosed = false
                }
                catch (error: Exception)
                {
                    println("TransmissionConnection: The socket failed to create a tcp connection with the provided host and port: $error")
                    logger?.log(Level.SEVERE, "The socket failed to create a tcp connection with the provided host and port: $error ")
                    throw error
                }
            }

            ConnectionType.UDP ->
            {
                try
                {
                    val socketAddress = InetSocketAddress(host, port)
                    this.connectionType = ConnectionType.UDP
                    this.udpConnection = DatagramSocket()
                    this.udpConnection!!.connect(socketAddress)
                    this.connectionClosed = false
                }
                catch (error: Exception)
                {
                    println("The socket failed to create a udp connection with the provided host and port: $error")
                    logger?.log(Level.SEVERE, "The socket failed to create a udp connection with the provided host and port: $error ")
                    throw error
                }
            }
        }
    }

    // Use this when you have already created a socket and connect() has been called
    constructor(tcpConnection: Socket, logger: Logger?) : this(logger)
    {
        this.tcpConnection = tcpConnection
        this.connectionClosed = false
    }

    constructor(udpConnection: DatagramSocket, logger: Logger?) : this(logger)
    {
        this.connectionType = ConnectionType.UDP
        this.udpConnection = udpConnection
        this.connectionClosed = false
    }

    override fun networkRead(size: Int): ByteArray?
    {
        logger?.log(Level.FINE, "Network Read: (size: $size)")
        val networkBuffer = ByteArray(size)
        var bytesRead = 0

        while (bytesRead < size)
        {
            try
            {
                when (connectionType)
                {
                    ConnectionType.TCP ->
                    {
                        if (tcpConnection == null)
                        {
                            logger?.log(Level.FINE, "TransmissionConnection: networkRead(size: ) called on null tcp connection.")
                            close()
                            return null
                        }

                        val readResult = tcpConnection!!.inputStream.read(networkBuffer, bytesRead, size - bytesRead)

                        if (readResult > 0)
                        {
                            bytesRead += readResult
                        }
                        else
                        {
                            close()
                            return null
                        }
                    }
                    ConnectionType.UDP ->
                    {
                        logger?.log(Level.SEVERE, "TransmissionConnection: Network read is not available for UDP connections.")
                        close()
                        return null
                    }
                }
            }
            catch (readError: Exception)
            {
                logger?.log(Level.SEVERE, "TransmissionConnection: Connection inputStream encountered an error while trying to read a specific size: $readError")
                readError.printStackTrace()
                close()
                return null
            }
        }

        return networkBuffer
    }

    override fun networkWrite(data: ByteArray): Boolean
    {
        try
        {
            when (connectionType)
            {
                ConnectionType.TCP ->
                {
                    if (tcpConnection == null)
                    {
                        logger?.log(Level.FINE, "TransmissionConnection: Called networkWrite() on a null tcpConnection")
                        close()
                        return false
                    }

                    tcpConnection!!.outputStream.write(data)
                    tcpConnection!!.outputStream.flush()
                    return true
                }
                ConnectionType.UDP ->
                {
                    if (udpConnection == null)
                    {
                        logger?.log(Level.FINE, "TransmissionConnection: Tried to call networkWrite() on a null udpConnection.")
                        close()
                        return false
                    }

                    val datagramPacket = DatagramPacket(data, data.size)

                    udpConnection!!.send(datagramPacket)
                    return true
                }
            }
        }
        catch (writeError: Exception)
        {
            logger?.log(Level.SEVERE, "TransmissionConnection: Error while attempting to write data to the network: $writeError")
            close()
            return false
        }
    }

    override fun networkClose()
    {
        if (udpConnection != null)
        {
            udpConnection!!.close()
        }
        else if (tcpConnection != null)
        {
            tcpConnection!!.close()
        }
    }
}