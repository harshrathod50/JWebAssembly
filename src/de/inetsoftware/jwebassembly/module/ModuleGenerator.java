/*
 * Copyright 2017 - 2018 Volker Berlin (i-net software)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.inetsoftware.jwebassembly.module;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import de.inetsoftware.classparser.ClassFile;
import de.inetsoftware.classparser.Code;
import de.inetsoftware.classparser.CodeInputStream;
import de.inetsoftware.classparser.LocalVariableTable;
import de.inetsoftware.classparser.MethodInfo;
import de.inetsoftware.jwebassembly.JWebAssembly;
import de.inetsoftware.jwebassembly.WasmException;
import de.inetsoftware.jwebassembly.watparser.WatParser;

/**
 * Generate the WebAssembly output.
 * 
 * @author Volker Berlin
 */
public class ModuleGenerator {

    private final ModuleWriter              writer;

    private final URLClassLoader            libraries;

    private final JavaMethodWasmCodeBuilder javaCodeBuilder = new JavaMethodWasmCodeBuilder();

    private final WatParser                 watParser = new WatParser();

    private String                          sourceFile;

    private String                          className;

    private FunctionManager                 functions = new FunctionManager();

    /**
     * Create a new generator.
     * 
     * @param writer
     *            the target writer
     * @param libraries
     *            libraries 
     */
    public ModuleGenerator( @Nonnull ModuleWriter writer, List<URL> libraries ) {
        this.writer = writer;
        this.libraries = new URLClassLoader( libraries.toArray( new URL[libraries.size()] ) );
    }

    /**
     * Prepare the content of the class.
     * 
     * @param classFile
     *            the class file
     * @throws WasmException
     *             if some Java code can't converted
     */
    public void prepare( ClassFile classFile ) {
        iterateMethods( classFile, m -> prepareMethod( m ) );
    }

    /**
     * Finish the prepare after all classes/methods are prepare. This must be call before we can start with write the
     * first method.
     */
    public void prepareFinish() {
        writer.prepareFinish();
    }

    /**
     * Write the content of the class to the writer.
     * 
     * @param classFile
     *            the class file
     * @throws WasmException
     *             if some Java code can't converted
     */
    public void write( ClassFile classFile ) throws WasmException {
        iterateMethods( classFile, m -> writeMethod( m ) );
    }

    /**
     * Finish the code generation.
     */
    public void finish() {
        
    }

    /**
     * Iterate over all methods of the classFile and run the handler.
     * 
     * @param classFile
     *            the classFile
     * @param handler
     *            the handler
     * @throws WasmException
     *             if some Java code can't converted
     */
    private void iterateMethods( ClassFile classFile, Consumer<MethodInfo> handler ) throws WasmException {
        sourceFile = null; // clear previous value for the case an IO exception occur
        className = null;
        try {
            sourceFile = classFile.getSourceFile();
            className = classFile.getThisClass().getName();
            MethodInfo[] methods = classFile.getMethods();
            for( MethodInfo method : methods ) {
                Code code = method.getCode();
                if( method.getName().equals( "<init>" ) && method.getType().equals( "()V" )
                                && code.isSuperInitReturn( classFile.getSuperClass() ) ) {
                    continue; //default constructor
                }
                handler.accept( method );
            }
        } catch( IOException ioex ) {
            throw WasmException.create( ioex, sourceFile, className, -1 );
        }
    }

    /**
     * Prepare the method.
     * 
     * @param method
     *            the method
     * @throws WasmException
     *             if some Java code can't converted
     */
    private void prepareMethod( MethodInfo method ) throws WasmException {
        try {
            FunctionName name = new FunctionName( method );
            Map<String,Object> annotationValues = method.getAnnotation( JWebAssembly.IMPORT_ANNOTATION );
            if( annotationValues != null ) {
                functions.writeFunction( name );
                String impoarModule = (String)annotationValues.get( "module" );
                String importName = (String)annotationValues.get( "name" );
                writer.prepareImport( name, impoarModule, importName );
                writeMethodSignature( method.getType(), null, null );
            } else {
                writer.prepareFunction( name );
            }
        } catch( Exception ioex ) {
            throw WasmException.create( ioex, sourceFile, className, -1 );
        }
    }

    /**
     * Write the content of a method.
     * 
     * @param method
     *            the method
     * @throws WasmException
     *             if some Java code can't converted
     */
    private void writeMethod( MethodInfo method ) throws WasmException {
        CodeInputStream byteCode = null;
        try {
            if( method.getAnnotation( JWebAssembly.IMPORT_ANNOTATION  ) != null ) {
                return;
            }
            WasmCodeBuilder codeBuilder;
            Code code = method.getCode();
            String signature;
            if( method.getAnnotation( JWebAssembly.TEXTCODE_ANNOTATION  ) != null ) {
                Map<String, Object> wat = method.getAnnotation( JWebAssembly.TEXTCODE_ANNOTATION  );
                String watCode = (String)wat.get( "value" );
                signature = (String)wat.get( "signature" );
                if( signature == null ) {
                    signature = method.getType();
                }
                watParser.parse( watCode, code == null ? -1 : code.getFirstLineNr() );
                codeBuilder = watParser;
            } else if( code != null ) { // abstract methods and interface methods does not have code
                signature = method.getType();
                javaCodeBuilder.buildCode( code, !method.getType().endsWith( ")V" ) );
                codeBuilder = javaCodeBuilder;
            } else {
                return;
            }
            FunctionName name = new FunctionName( method );
            writeExport( name, method );
            writer.writeMethodStart( name );
            functions.writeFunction( name );
            writeMethodSignature( signature, code.getLocalVariableTable(), codeBuilder );

            for( WasmInstruction instruction : codeBuilder.getInstructions() ) {
                if( instruction instanceof WasmCallInstruction ) {
                    functions.functionCall( ((WasmCallInstruction)instruction).getFunctionName() );
                }
                instruction.writeTo( writer );
            }
            writer.writeMethodFinish();
        } catch( Exception ioex ) {
            int lineNumber = byteCode == null ? -1 : byteCode.getLineNumber();
            throw WasmException.create( ioex, sourceFile, className, lineNumber );
        }
    }

    /**
     * Look for a Export annotation and if there write an export directive.
     * 
     * @param name
     *            the function name
     * @param method
     *            the method
     * 
     * @throws IOException
     *             if any IOException occur
     */
    private void writeExport( FunctionName name, MethodInfo method ) throws IOException {
        Map<String,Object> export = method.getAnnotation( JWebAssembly.EXPORT_ANNOTATION );
        if( export != null ) {
            String exportName = (String)export.get( "name" );
            if( exportName == null ) {
                exportName = method.getName();  // TODO naming conversion rule if no name was set
            }
            writer.writeExport( name, exportName );
        }
    }

    /**
     * Write the parameter and return signatures
     * 
     * @param signature
     *            the Java signature, typical method.getType();
     * @param variables
     *            Java variable table with names of the variables for debugging
     * @param codeBuilder
     *            the calculated variables 
     * @throws IOException
     *             if any I/O error occur
     * @throws WasmException
     *             if some Java code can't converted
     */
    private void writeMethodSignature( String signature, @Nullable LocalVariableTable variables, WasmCodeBuilder codeBuilder ) throws IOException, WasmException {
        String kind = "param";
        int paramCount = 0;
        ValueType type = null;
        for( int i = 1; i < signature.length(); i++ ) {
            if( signature.charAt( i ) == ')' ) {
                kind = "result";
                continue;
            }
            String name = null;
            if( kind == "param" ) {
                if( variables != null ) {
                    name = variables.getPosition( paramCount ).getName();
                }
                paramCount++;
            }
            type = ValueType.getValueType( signature, i );
            if( type != null ) {
                writer.writeMethodParam( kind, type, name );
            }
        }
        if( codeBuilder != null ) {
            List<ValueType> localTypes = codeBuilder.getLocalTypes( paramCount );
            for( int i = 0; i < localTypes.size(); i++ ) {
                type = localTypes.get( i );
                String name = null;
                if( variables != null ) {
                    name = variables.getPosition( paramCount ).getName();
                }
                writer.writeMethodParam( "local", type, name );
            }
        }
        writer.writeMethodParamFinish( );
    }

}
