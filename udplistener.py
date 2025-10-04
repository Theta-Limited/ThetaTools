# udplistener.py
# use this to listen on UDP port and print out 
# contents of datagrams received; used for testing
# CoT over UDP
# Test this program with this simple command-line call to nc
# echo '<test>hello</test>' | nc -u 127.0.0.1 12345

import socket
import sys

def main():
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <UDP_PORT>")
        sys.exit(1)

    UDP_PORT = int(sys.argv[1])

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(('0.0.0.0', UDP_PORT))

    print(f"Listening for UDP datagrams on port {UDP_PORT}... (Ctrl+C to quit)")

    try:
        while True:
            data, addr = sock.recvfrom(65535)  # Buffer size is 65535 bytes
            print(f"\nReceived packet from UDP {addr}:")
            print(data.decode('utf-8', errors='replace'))
    except KeyboardInterrupt:
        print("\nExiting.")
    finally:
        sock.close()

if __name__ == "__main__":
    main()
    
