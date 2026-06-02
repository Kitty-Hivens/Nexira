package hivens.widget.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

// Field-level metadata for widget prop classes. These are @SerialInfo
// annotations: the serialization compiler plugin copies them into the
// SerialDescriptor, so the prop editor reads them off
// descriptor.getElementAnnotations(i) at runtime with no reflection.
//
// A field's serial kind (Boolean/Int/Float/String/enum) picks the base
// editor control; these annotations refine it. They live in widget-model
// so prop classes stay Compose-free and serializable.
//
// Retention is left at the Kotlin default (RUNTIME) on purpose --
// @SerialInfo annotations must be RUNTIME to survive into the descriptor.

// Human-facing label shown in the editor. Without it the editor falls
// back to the serial element name.
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class PropLabel(val value: String)

// Numeric bounds for an Int/Float field -- renders a slider instead of a
// free-entry field. step == 0.0 means continuous (the control rounds
// Int fields to whole steps itself).
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class PropRange(val min: Double, val max: Double, val step: Double = 0.0)

// Marks a String field as a hex colour ("#RRGGBB" / "#AARRGGBB"; ""
// means "fall back to the theme"). Renders the swatch + hex control.
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class PropColor

// Fixed option set for a String field -- renders a dropdown. (enum
// fields already render a dropdown from their serial kind; this is for
// String-typed choices that are not a Kotlin enum.)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class PropChoice(val options: Array<String>)

// Excludes a field from the editor form. Still serialized and readable
// by the widget -- for computed or internal props the user must not tune.
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class PropHidden
