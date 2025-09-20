Esta app se realizo solo con un fin, poder leer mangas en otros idiomas, ya muchos scan estan cerrando y algunos mangas / manwhas no estan licenciados al español, sepan disculpar los errores, es mi primera app en kotlin, 

la app genera "textview" en pequeños contenedores con las traducciones.
se recomiendo celulares con ANDROID 10 Y MAS DE 2GB RAM, menos de esa memoria se vuelve lento ya que carga los idiomas y ejecuta todo de manera offline.

Características principales del código:
🎯 Funcionalidad del Botón Flotante:

Presiona "Captura" para que la app genera una captura de pantalla inmediata
1 segundo despues Oculta el overlay del boton para extraer el texto y desdepues mostrarlo en el texview de traducción

🔍 OCR con ML Kit:

Detección automática de idioma si se selecciona "Auto"
Reconocimiento de texto en tiempo real
Manejo de múltiples bloques de texto

🌍 Traducción:

Descarga automática de modelos de idioma
Soporte para múltiples idiomas (ES, EN, FR, DE, IT, PT, KO, JP, CH)
Traducción en tiempo real

📱 Overlay Inteligente:

Respeta las coordenadas originales del texto
Fondo semi-transparente para mejor legibilidad
Se superpone exactamente donde estaba el texto original

⚙️ Configuración:

Menú flotante para selección de idiomas
Persistencia de configuración durante la sesión
Interfaz intuitiva y fácil de usar


<img width="347" height="782" alt="image" src="https://github.com/user-attachments/assets/55eb1dae-ef6b-4b18-bc7d-1f418b932479" /> <img width="360" height="798" alt="image" src="https://github.com/user-attachments/assets/6e2da922-6862-4134-8693-ebf56e255cc3" /> <img width="360" height="839" alt="image" src="https://github.com/user-attachments/assets/0de7c6a2-a05e-43c2-82bd-7aa3a8738978" />

<img width="349" height="798" alt="image" src="https://github.com/user-attachments/assets/459e8e8e-2208-4412-bd5b-5b55fce978c9" /> <img width="351" height="794" alt="image" src="https://github.com/user-attachments/assets/a10eaa39-cd31-4997-b2ef-cf072e347ca3" />

* version 4.5 / implemetacion de boton para captura continua - al presionar el boton "capturar" por mas de 3 segundos inicia un bucle realizando de forma automatica cada 10 segundos una nueva traduccion - se desactiva al presionar el boton "continuo".

* verisom 4.4 / mejora en la forma de captura del ocr para mejorar la traduccion .

* Version 4.3 /  se reparo el error de la supeposicion de textview, se implemento un recorte en la funcion de OCR donde omite la barra de estado desde android 10 hasta el 14, desde 15 el mismo sistema omite la captura de la barra y los botones virtuales. 

* version 4.1 del alfa, tiene errores en colision de los texview, parpadeo en las traducciones mostrando por menos de 2 segundos y cerrando el contenedor del la traduccon, en japones algunos mamgas con mucho texto se vuelve inviable el uso ya que se suporponen los mismos textview impidiento la lectura, por momento son los errores que encontre y estoy por solucionar.
  
* nota: no tengo imaginacion para los nombres.
* esta app durante el proceso se uso IA en algunas partes del codigo, ya que aun estoy aprendiendo kotlin, por lo que podran encontrar algunos comentarios explicativos en partes del codigo.
* por si quieren probar la app ya compilada - https://gofile.io/d/VjH42x
