# udpmcastclient.py
# UDP mcast client for use with OA CoT testing
# Bobby Krupczak
# rdk@theta.limited

# CC0 1.0
# https://creativecommons.org/publicdomain/zero/1.0/deed.en

import os, socket, sys, getopt

# 3 second timeout
timeout = 3

if len(sys.argv) != 4:
    print("usage: udpmcastclient serverName serverPort sendingIfIP")
    sys.exit(-1)
else:
    serverName = sys.argv[1]
    serverPort = sys.argv[2]
    sendingIfIP = sys.argv[3]

serverPort = int(serverPort)

sock = socket.socket(socket.AF_INET,socket.SOCK_DGRAM)

# add a timeout in case the request or reply are lost
sock.settimeout(timeout)

# how many hops max for mcast packet to travel
sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL,5)

# which interface to send mcast packets out of
sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(sendingIfIP))

serverAddress = (serverName,serverPort)

while True:
    aStr = input("Enter string to send: ")
    if aStr == "break":
        break
    aStr = aStr + "\n\n"
    byteStr = aStr.encode()
    sock.sendto(byteStr,serverAddress)

    # use this portion with no settimeout
    #(reply,fromAddress) = sock.recvfrom(512)
    #replyStr = reply.decode()
    #replyStr = replyStr.rstrip('\n')
    #print("Received back: '",replyStr,"' from ",fromAddress,sep="")

    try:
        (reply,fromAddress) = sock.recvfrom(512)
        replyStr = reply.decode()
        replyStr = replyStr.rstrip('\n')
        print("Received back: '",replyStr,"' from ",fromAddress,sep="")

    except Exception as e:
        print("Error receiving reply ",e)

