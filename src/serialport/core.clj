(ns serialport.core
  (:import
    (gnu.io CommPortIdentifier
            SerialPort
            SerialPortEventListener
            SerialPortEvent)
    (java.io OutputStream
             InputStream))
  (:require [gloss.core :as gloss]
            [gloss.io :as gio]
            [clojure.string :as str]))

(def ^:dynamic *databits*
  "define the databits map."
  {:five  SerialPort/DATABITS_5
   :six   SerialPort/DATABITS_6
   :even SerialPort/DATABITS_7
   :eight SerialPort/DATABITS_8})

(def ^:dynamic *parities*
  "define the parity map."
  {:even SerialPort/PARITY_EVEN
   :mark SerialPort/PARITY_MARK
   :none SerialPort/PARITY_NONE
   :odd  SerialPort/PARITY_ODD
   :space SerialPort/PARITY_SPACE})

(def ^:dynamic *stopbits*
  "define the stopbit map."
  {:one SerialPort/STOPBITS_1
   :one-five SerialPort/STOPBITS_1_5
   :two SerialPort/STOPBITS_2})

(def ^:dynamic *flowcontrols*
  "define the flow control."
  {:none SerialPort/FLOWCONTROL_NONE
   :rtscts-in SerialPort/FLOWCONTROL_RTSCTS_IN
   :rtscts-out SerialPort/FLOWCONTROL_RTSCTS_OUT
   :xonxoff-in SerialPort/FLOWCONTROL_XONXOFF_IN
   :xonxoff-out SerialPort/FLOWCONTROL_XONXOFF_OUT})

(def ^:dynamic *port-open-timeout* 2000)

(def BUFFER-MAX-LEN  4096)

(defrecord Port [name raw-port in-stream out-stream
                 recved-event sent-event frame-error-event])

(defn ports
  "get all the system specified communication ports, not just the serial port."
  []
  (enumeration-seq (CommPortIdentifier/getPortIdentifiers)))

(defn list-ports
  "Print all system visible ports."
  []
  (loop [ports (ports)
         index 1]
    (when ports
      (println index ": " (.getName (first ports)))
      (recur (next ports) (inc index)))))


(defn get-port
  "Return the port from the port list given by the port name."
  [name]
  (let [name (str/lower-case name)]
    (first (filter #(str/lower-case (.getName %)) (ports)))))

(defn open
  "open a port given port name and other parameters and return the por."
  ([name & {:keys [baud databit parity stopbit flowcontrol] 
            :or {baud 115200
                 databit :eight
                 parity :none
                 stopbit :one
                 flowcontrol :none}}] 
   (try
     (let [uuid (.toString (java.util.UUID/randomUUID))
           port-id (get-port name)
           raw-port (.open port-id uuid *port-open-timeout*)
           out (.getOutputStream raw-port)
           in  (.getInputStream raw-port)
           _   (.setSerialPortParams raw-port baud
                                     (*databits* databit)
                                     (*stopbits* stopbit)
                                     (*parities* parity))
           _   (.setFlowControlMode raw-port (*flowcontrols* flowcontrol))

           ;; create the port with the empty event handlers.
           port (Port. name raw-port in out (atom nil) (atom nil) (atom nil))

           ;; byte buffer create.
           buffer (byte-array BUFFER-MAX-LEN)
           
           ;; add the serial port event handler.
           listener (reify SerialPortEventListener
                      (serialEvent [_ event]
                        (let [event-type (.getEventType event)]
                          (cond
                            ;; new data arrival
                            (= event-type SerialPortEvent/DATA_AVAILABLE)
                            (loop [len (.available in)
                                   total 0
                                  count (.read in buffer total len)]
                              (if (and len (< total BUFFER-MAX-LEN))
                                (let [total (+ total count)]
                                  (recur (.available in)
                                         total
                                         (.read in buffer total len)))
                                (doseq [recv @(:recved-event port)]
                                  (recv (byte-array total buffer)))))

                            ;; data has been sent event.
                            (= event-type SerialPortEvent/OUTPUT_BUFFER_EMPTY)
                            (doseq [sent @(:sent-event port)]
                              (sent))

                            (or (= event-type SerialPortEvent/FE)
                                (= event-type SerialPortEvent/PE))
                            (doseq [error @(:frame-error-event port)]
                              (error))))))]

       ;; skip the bufer in the port input stream.
       (.skip in (.available in))
       
       ;; add the event listener.
       (doto raw-port
         (.addEventListener listener)
         (.notifyOnDataAvailable true)
         (.notifyOnOutputEmpty true)
         (.notifyOnParityError true)
         (.notifyOnFramingError true))

       port)

     ;; exception handle
     (catch Exception e
       (println (.toString e))
       (throw (Exception. (str "Cannot open the port.")))))))

(defn close
  "close the port."
  [port]
  (let [raw-port (:raw-port port)]
    (.removeEventListener raw-port)
    (.close raw-port)))

(defn write
  "wrtie a byte array to a port, or something like the ByteBuffer from java.nio
  instance's array field."
  [port bytes]
  (.write ^OutputStream (:out-stream port) ^bytes bytes))

(defn- compose-byte-array
  [bytes]
  (byte-array (count bytes) (map #(.byteValue %) bytes)))

(defn write-ints
  "Write a seq of integer, convert to byte array and send."
  [port seq-of-ints]
  (write port (compose-byte-array seq-of-ints)))

(defn port-event-on
  "Add port event listener to the port, they are:
recved-event: (fn [bytes] (do something)),
sent-event: (fn [] (do sometihng)),
frame-error-event (fn [] (do something)).
each event is given as a map."
  [port & {:keys [recved-event sent-event frame-error-event]}]
  (when recved-event
    (swap! (:recved-event port) conj recved-event))
  (when sent-event
    (swap! (:sent-event port) conj sent-event))
  (when frame-error-event
    (swap! (:frame-error-event port) conj frame-error-event)))

(defn remove-event
  "Remove the event from the event handler list."
  [port & {:keys [recved-event sent-event frame-error-event]}]
  (when recved-event
    (swap! (:recved-event port) 
           (fn [events] (remove #(= recved-event %) events))))
  (when sent-event
    (swap! (:sent-event port)
           (fn [events] (remove #(= sent-event %) events))))
  (when frame-error-event
    (swap! (:frame-error-event port)
           (fn [events] (remove #(= frame-error-event %) events)))))

