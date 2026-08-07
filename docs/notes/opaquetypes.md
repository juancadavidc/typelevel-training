# Opaque Types (Scala 3)

> Nota de aprendizaje — 2026-08-07

## Qué es
Un **opaque type** es un alias de tipo de Scala 3 que, **fuera de su ámbito de definición**, es un tipo distinto e incompatible con el tipo subyacente, pero **en runtime no añade ningún coste** (no hay wrapping/boxing como en una `case class`).

## Por qué importa / cuándo usarlo
Da seguridad de tipos para *newtypes* del dominio (identificadores, valores) sin overhead:

- Un `type` normal es transparente → no protege contra mezclar valores.
- Una `case class` protege pero añade un objeto extra en memoria.
- Un `opaque type` combina lo mejor: protección en compilación + cero coste en ejecución.

Ideal para modelar `CustomerId`, `CouponCode`, `OrderId`, `Money`, etc. y evitar confundir un `OrderId` con un `CustomerId` aunque ambos sean `String`.

## Ejemplo
```scala
object domain:
  opaque type CustomerId = String

  object CustomerId:
    // smart constructor con validación (refleja @length(min: 1) del Smithy)
    def from(s: String): Either[String, CustomerId] =
      if s.nonEmpty then Right(s) else Left("customerId vacío")

    extension (id: CustomerId)
      def value: String = id

import domain.*
val id  = CustomerId.from("abc")  // Either[String, CustomerId]
// val s: String = id             // ❌ no compila: CustomerId no es String aquí
```

- **Dentro** del scope de definición, `CustomerId` y `String` son intercambiables.
- **Fuera**, son tipos distintos → el compilador te protege.
- **En runtime** es literalmente un `String`.

## Notas / gotchas
- El acceso al valor subyacente se suele exponer con un `extension` (`.value`) o un método en el companion.
- Los constructores viven en el companion `object` para poder validar (smart constructors).
- smithy4s genera newtypes propios para los tipos del Smithy, así que a veces no necesitas escribir el opaque type a mano — pero conocerlo ayuda a entender lo generado y a modelar valores extra del dominio.
- Se pueden restringir con límites: `opaque type Positive <: Int = Int`.
