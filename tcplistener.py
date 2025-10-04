# tcplistener.py
# use this to listen on tcp port and print out 
# contents of datagrams received; used for testing
# CoT over TCP
# Test this program with this simple command-line call to nc
# echo '<test>hello</test>' | nc 127.0.0.1 12345

import socket
import sys
import threading

def handle_client(conn, addr):
    print(f"\nAccepted connection from {addr}")
    try:
        while True:
            data = conn.recv(4096)
            if not data:
                print(f"Connection from {addr} closed by client.")
                break
            print(f"From TCP {addr}: {data.decode('utf-8', errors='replace').rstrip()}")
    except Exception as e:
        print(f"Error with {addr}: {e}")
    finally:
        conn.close()

def main():
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <TCP_PORT>")
        sys.exit(1)

    TCP_PORT = int(sys.argv[1])

    server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_sock.bind(('0.0.0.0', TCP_PORT))
    server_sock.listen(5)

    print(f"Listening for TCP connections on port {TCP_PORT}... (Ctrl+C to quit)")

    try:
        while True:
            conn, addr = server_sock.accept()
            client_thread = threading.Thread(target=handle_client, args=(conn, addr), daemon=True)
            client_thread.start()
    except KeyboardInterrupt:
        print("\nExiting.")
    finally:
        server_sock.close()

if __name__ == "__main__":
    main()
