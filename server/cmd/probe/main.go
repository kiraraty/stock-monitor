package main
import("fmt";"time";"os";"github.com/gorilla/websocket")
func main(){for _,u:=range os.Args[1:]{c,_,e:=websocket.DefaultDialer.Dial(u,nil);if e!=nil{fmt.Println(e);continue};c.SetReadDeadline(time.Now().Add(10*time.Second));_,b,e:=c.ReadMessage();fmt.Println(u,string(b),e);c.Close()}}
