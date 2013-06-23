# serialport

A Clojure library designed to serial port.

## Usage

* first, open a port using this code:

``` clojure
(use 'serialport.core)
(def port (open "com1" :baud 19200 :databit :eight :parity :none :stopbit :one))
```

* second, add some event listener to this opened port, including
recved-event, sent-event and frame-error-event, using this code:

``` clojure
(port-event-on port
  :recved-event (fn [bytes] (println "Recv data: " (vec bytes)))
  :sent-event (fn [] (println "Data has been sent."))
  :frame-error-event (fn [] (println "Something is wrong.")))
```

* finally, write some data to the port.

``` clojure
(write port (byte-array (map byte [1 2 2 3 4 4 34 34])))
(write-ints port [1 2 2 3 4 5 5 5])
```

* at the end. close the serial port.

``` clojure
(close port))
```


You can use the gloss library to encode binary data, then send to the serial port; when recving some data from the serial port, you can also use the gloss library's decode function to decode the binary data, returnning the specified data structure that you want. 

Example:

``` clojure
(use '[gloss core io])
(import '[java.nio ByteBuffer HeapByteBuffer])
(def fr (compile-frame {:a :int32-le :b :float32-le}))
(->> (encode fr {:a 1 :b 12.3}) first .array (write port))

;; for recv data processing.
(port-event-on port
  :recved-event (fn [bytes]
                  (let [head (decode fr (byte-array 8 bytes))]
                     (if (= something (:a head))
                       (do something else....)))))
```

## License

Copyright © 2013 likon

Distributed under the Eclipse Public License, the same as Clojure.
