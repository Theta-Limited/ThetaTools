# udpmcastserver.py
# UDP Multicast Server for use OA CoT testing
# Bobby Krupczak
# rdk@theta.limited

# CC0 1.0
# https://creativecommons.org/publicdomain/zero/1.0/deed.en


import os, socket, sys, getopt, select

# socket list for selecting
inputs = [ ] 

# ----------------------------------------------------
# receive a message as stream of chars until \n\n
# caller can strip the \n\n

# or end of data? XXX

def recvMessage(sock):
    buf = b''
    bufStr = ""
    while True:
      (newbuf,fromAddress) = sock.recvfrom(2048)
      if not newbuf:
          return (None,None)
      buf += newbuf
      bufStr += buf.decode()
      if bufStr.find('\n\n') >= 0:
         break;
      if bufStr.find('\r\n\r\n') >= 0:
         break;

    # print "recvMessage received ",len(buf)," bytes"
    return (bufStr,fromAddress)

# receive what can be had from a single
# call to recv for use with testing Cursor on Target (CoT) messages
# 
def recvSingleMessage(sock):
    buf = b''
    bufStr = ""
    (newbuf,fromAddress) = sock.recvfrom(2048)
    if not newbuf:
       return (None,None)
    buf += newbuf
    bufStr += buf.decode()

    # print "recvMessage received ",len(buf)," bytes"
    return (bufStr,fromAddress)

# ----------------------------------------------------
# process a message by receiving then echoing back
# to sender

def processMessage(sock):
    try:
        (data,fromAddress) = recvSingleMessage(sock)
        if data is None:
            return -1
        data = data.rstrip('\n')
        print("processMessage: received from ",fromAddress);
        print("processMessage: received: '",data);
        reply = data+"\n\n"
        reply = reply.encode()
        sock.sendto(reply,fromAddress)
        return 1
    
    except:
        print("processMessage: some sort of error")
        return -1
        
# ----------------------------------------------------
# main

if len(sys.argv) != 3:
    print("Usage: udpmcastserver <listen-ip> <portno> ")
    sys.exit(-1)

print("Listen ip is "+sys.argv[1])
print("Port is "+sys.argv[2])

serverSock = socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
serverSock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
serverSock.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_TTL,4)
serverSock.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_LOOP,1)
serverAddress = ('239.2.3.1',int(sys.argv[2]))
serverSock.bind(serverAddress)

# set the mcast interface 
serverSock.setsockopt(socket.SOL_IP,socket.IP_MULTICAST_IF,socket.inet_aton(sys.argv[1]))
# what ip address/interface do we want to add membership
serverSock.setsockopt(socket.SOL_IP,socket.IP_ADD_MEMBERSHIP, socket.inet_aton('239.2.3.1') + socket.inet_aton(sys.argv[1]))

inputs.append(serverSock)        

while True:

    print("Going to select over list of sockets . . .",len(inputs))

    try:
        readable, writable, exceptional = select.select(inputs,[],inputs)

        for s in exceptional:
            print("select exception with ",s)
            
            # if s != serverSock:
            #    inputs.remove(s)
            #    s.close(s)
            
        for s in readable:
            try:
                processMessage(s)
                sys.stdout.flush()
                sys.stderr.flush()

            except Exception as e:
                print("Error reading from server socket")
                print(e)

    except Exception as e:
        print("Server select error . . . .")
        print(e)
        
