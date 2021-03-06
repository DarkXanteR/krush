package pl.touk.krush.model

import com.squareup.kotlinpoet.metadata.ImmutableKmType
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import com.squareup.kotlinpoet.metadata.toImmutableKmClass
import kotlinx.metadata.KmClassifier
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import pl.touk.krush.env.AnnotationEnvironment
import pl.touk.krush.env.TypeEnvironment
import pl.touk.krush.env.enclosingTypeElement
import pl.touk.krush.env.toTypeElement
import pl.touk.krush.validation.ConverterTypeNotFoundException
import pl.touk.krush.validation.ElementTypeNotFoundException
import pl.touk.krush.validation.EntityNotMappedException
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.AnnotationValue
import javax.lang.model.element.Name
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeMirror
import javax.persistence.AttributeConverter
import javax.persistence.AttributeOverride
import javax.persistence.AttributeOverrides
import javax.persistence.Column
import javax.persistence.Convert
import javax.persistence.Enumerated
import javax.persistence.GeneratedValue

@KotlinPoetMetadataPreview
class ColumnProcessor(override val typeEnv: TypeEnvironment, private val annEnv: AnnotationEnvironment) : ElementProcessor {

    override fun process(graphs: EntityGraphs) {
        processIds(graphs)
        processColumns(graphs)
        processEmbeddedColumns(graphs)
    }

    private fun processIds(graphs: EntityGraphs) =
            processElements(annEnv.ids, graphs) { entity, idElt ->
                val columnAnn: Column? = idElt.getAnnotation(Column::class.java)
                val columnName = getColumnName(columnAnn, idElt)
                val converter = getConverterDefinition(idElt)
                val genValAnn: GeneratedValue? = idElt.getAnnotation(GeneratedValue::class.java)
                val generatedValue = genValAnn?.let { true } ?: false
                val type = idElt.toModelType() ?: throw ElementTypeNotFoundException(idElt)

                val idDef = IdDefinition(
                        name = idElt.simpleName, columnName = columnName, converter = converter,
                        annotation = columnAnn, type = type, generatedValue = generatedValue,
                        nullable = isNullable(idElt)
                )
                entity.copy(id = idDef)
            }

    private fun processColumns(graphs: EntityGraphs) =
            processElements(annEnv.columns, graphs) { entity, columnElt ->
                val columnDefinition = propertyDefinition(columnElt)
                entity.addProperty(columnDefinition)
            }

    private fun processEmbeddedColumns(graphs: EntityGraphs) {
        for (element in annEnv.embedded) { // @Entity User(@Embedded InvoiceAddress element)
            val embeddableType = (element.asType() as DeclaredType).asElement().toTypeElement() // InvoiceAddress
            val columns = annEnv.embeddedColumn.filter { columnElt -> columnElt.enclosingTypeElement() == embeddableType }.toList() //city, street, houseNumber
            val entityType = element.enclosingTypeElement() //User

            val graph = graphs[entityType.packageName] ?: throw EntityNotMappedException(entityType)
            graph.computeIfPresent(entityType) { _, entity ->
                //User
                val columnDefs = columns.map { column -> propertyDefinition(column, element.mappingOverride()) }
                val embeddable = EmbeddableDefinition(propertyName = element.simpleName,
                        qualifiedName = embeddableType.qualifiedName, nullable = isNullable(element), properties = columnDefs)

                entity.addEmbeddable(embeddable)
            }
        }
    }

    private fun propertyDefinition(columnElt: VariableElement, overrideMapping: List<AttributeOverride> = emptyList()): PropertyDefinition {
        val columnAnn: Column? = columnElt.getAnnotation(Column::class.java)
        val name = columnElt.simpleName
        val columnName = overrideMapping.overriddenName(name) ?: getColumnName(columnAnn, columnElt)
        val converter = getConverterDefinition(columnElt)
        val enumerated = getEnumeratedDefinition(columnElt)
        val type = columnElt.toModelType() ?: throw ElementTypeNotFoundException(columnElt)
        return PropertyDefinition(name = name, columnName = columnName, annotation = columnAnn, type = type,
                nullable = isNullable(columnElt), converter = converter, enumerated = enumerated)
    }

    private fun isNullable(columnElt: VariableElement) =
            columnElt.getAnnotation(NotNull::class.java) == null && columnElt.getAnnotation(Nullable::class.java) != null

    private fun getColumnName(columnAnn: Column?, columnElt: VariableElement): Name = when {
        columnAnn == null || columnAnn.name.isEmpty() -> columnElt.simpleName
        else -> columnAnn.name.name()
    }

    private fun Iterable<AttributeOverride>.overriddenName(basicName: Name): Name? =
            this.singleOrNull { it.name == basicName.asVariable() }?.column?.name?.name()

    private fun getConverterDefinition(columnElt: VariableElement): ConverterDefinition? {
        val converterType = columnElt.annotationMirror(Convert::class.java.canonicalName)?.valueType("converter")

        return converterType?.let {
            val databaseType = converterType.toTypeElement().toImmutableKmClass().functions
                    .find { it.name == AttributeConverter<*, *>::convertToDatabaseColumn.name }
                    ?.returnType?.toModelType() ?: throw ConverterTypeNotFoundException(converterType)

            return ConverterDefinition(name = converterType.qualifiedName.asVariable(), targetType = databaseType)
        }
    }

    private fun getEnumeratedDefinition(columnElt: VariableElement): EnumeratedDefinition? {
        return columnElt.annotationMirror(Enumerated::class.java.canonicalName)?.value("value")
                ?.value?.toString()?.let { EnumeratedDefinition(EnumType.valueOf(it)) }
    }

    private fun VariableElement.annotationMirror(className: String): AnnotationMirror? {
        for (mirror in this.annotationMirrors) {
            if (typeEnv.isSameType(mirror.annotationType, className)) {
                return mirror
            }
        }
        return null
    }

    private fun AnnotationMirror.valueType(valueName: String): TypeElement? {
        val annotationValue = this.value(valueName) ?: return null

        val value = annotationValue.value as TypeMirror
        return typeEnv.typeUtils.asElement(value) as TypeElement
    }

    private fun AnnotationMirror.value(valueName: String): AnnotationValue? {
        for (entry in this.elementValues.entries) {
            if (entry.key.simpleName.toString() == valueName) {
                return entry.value
            }
        }
        return null
    }

    private fun VariableElement.toModelType(): Type? {
        return this.enclosingElement.toTypeElement().toImmutableKmClass().properties
                .find { it.name == this.simpleName.toString() }
                ?.returnType
                ?.toModelType()
    }

    private fun VariableElement.mappingOverride(): List<AttributeOverride> {
        return (this.getAnnotation(AttributeOverrides::class.java)?.value?.toList() ?: emptyList()) +
                (this.getAnnotation(AttributeOverride::class.java)?.let { listOf(it) } ?: emptyList())
    }

    private fun ImmutableKmType.toModelType(): Type? {
        return (this.classifier as KmClassifier.Class).name
                .split("/").let { Type(it.dropLast(1).joinToString(separator = "."), it.last()) }
    }

    private fun String.name(): Name = typeEnv.elementUtils.getName(this)
}
