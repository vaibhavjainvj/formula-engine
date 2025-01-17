/*
 * Copyright, 1999-2007, salesforce.com
 * All Rights Reserved
 * Company Confidential
 */
package com.force.formula.commands;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Deque;

import com.force.formula.FormulaCommand;
import com.force.formula.FormulaCommandType.AllowedContext;
import com.force.formula.FormulaCommandType.SelectorSection;
import com.force.formula.FormulaContext;
import com.force.formula.FormulaDateTime;
import com.force.formula.FormulaEvaluationException;
import com.force.formula.FormulaException;
import com.force.formula.FormulaProperties;
import com.force.formula.FormulaRuntimeContext;
import com.force.formula.FormulaSchema;
import com.force.formula.FormulaTime;
import com.force.formula.impl.FormulaAST;
import com.force.formula.impl.FormulaTypeUtils;
import com.force.formula.impl.FormulaValidationHooks;
import com.force.formula.impl.JsValue;
import com.force.formula.impl.TableAliasRegistry;
import com.force.formula.impl.WrongArgumentTypeException;
import com.force.formula.impl.WrongNumberOfArgumentsException;
import com.force.formula.sql.SQLPair;
import com.force.formula.util.FormulaI18nUtils;
import com.force.i18n.BaseLocalizer;
import com.force.i18n.LabelReference;
import com.force.i18n.Renameable;

/**
 * Format a value into text in the Grammaticus format, or using a java based format the same as the one
 * used in apex:outputText
 * FORMAT(value[, format]);
 * Value can be a decimal, a date, or a datetime
 *
 * This also supports
 * FORMAT(messageFormat, [arg0, arg1, ...])
 * 
 * 
 * Note: this isn't fully supported, so it's marked internal only, as it doesn't work in javascript
 * or in the DB.  
 *
 * @author stamm
 * @since 150
 */
@AllowedContext(section=SelectorSection.DATE_TIME, access="beta",displayOnly=true, isJavascript=false)
public class FunctionFormat extends FormulaCommandInfoImpl implements FormulaCommandValidator {

    public static final String LOCAL_TIME_CORRECT = "time_correct";
    public static final String LOCAL_TIME = "time";
    public static final String LOCAL_DATE = "date";
    public static final String LOCAL_LONGDATE = "ldate";
    /*
     * Used in the new d'n'd calendar.  Tells the formatter to translate
     * day names based on the user's language, rather than their locale.
     */
    public static final String USE_LANGUAGE_PREFIX = "LANG:";

    public FunctionFormat() {
        super("FORMAT");
    }

    @Override
    public FormulaCommand getCommand(FormulaAST node, FormulaContext context) {
        return new FunctionFormatCommand(this, node.getNumberOfChildren());
    }


    @Override
    public SQLPair getSQL(FormulaAST node, FormulaContext context, String[] args, String[] guards, TableAliasRegistry registry)
        throws FormulaException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Type getReturnType(FormulaAST node, FormulaContext context) throws FormulaException {
        return String.class;
    }

    @Override
    public Type validate(FormulaAST node, FormulaContext context, FormulaProperties properties) throws FormulaException {
        int kids = node.getNumberOfChildren();
        if (kids == 0) {
            throw new WrongNumberOfArgumentsException(node.getText(), 1, node);
        }

        FormulaAST toConvert = (FormulaAST)node.getFirstChild();
        Type clazz = toConvert.getDataType();

        if ((clazz != ConstantNull.class) && (clazz != RuntimeType.class)
            && (clazz != BigDecimal.class) && (clazz != Date.class) && (clazz != FormulaTime.class)
            && (clazz != LabelReference.class)
            && (clazz != FormulaDateTime.class) && (!FormulaTypeUtils.isTypeText(clazz)))
            throw new WrongArgumentTypeException(node.getText(), new Type[] { clazz }, toConvert);

        Type resultType = String.class;
        if (clazz == RuntimeType.class) {
            resultType = clazz;
        }

        if (clazz == LabelReference.class) {  // Since LabelReference converts to a string, we have to put it before the isTypeTextUgly check
            // We can have have as many kids as we want here, but they all need to be entity references
            while (null != (toConvert = (FormulaAST) toConvert.getNextSibling())) {
                Type clazz2 = toConvert.getDataType();
                if ((clazz2 != ConstantNull.class) && clazz2 != FormulaSchema.Entity.class && clazz2 != Renameable.class && clazz2 != RuntimeType.class) {
                    throw new WrongArgumentTypeException(node.getText(), new Type[] { Renameable.class }, toConvert);
                }
                if (clazz2 == RuntimeType.class) {
                    resultType = clazz2;
                }
            }
        } else if (FormulaTypeUtils.isTypeTextUgly(clazz) || clazz == RuntimeType.class) {
            // We can have have as many kids as we want here, but they need to be suitable for MessageFormat (date, time, number, string)
            while (null != (toConvert = (FormulaAST) toConvert.getNextSibling())) {
                Type clazz2 = toConvert.getDataType();
                if ((clazz2 != ConstantNull.class) && (!FormulaTypeUtils.isTypeTextUgly(clazz2)) && (clazz2 != Object.class) && clazz2 != RuntimeType.class
                        && clazz2 != Date.class && clazz2 != FormulaDateTime.class && clazz2 != BigDecimal.class && clazz2 != FormulaTime.class) {
                    throw new WrongArgumentTypeException(node.getText(), new Type[] { clazz, clazz2 }, toConvert);
                }
            }
        } else if (kids == 2) {
            toConvert = (FormulaAST) toConvert.getNextSibling();
            Type clazz2 = toConvert.getDataType();

            if ((clazz2 != ConstantNull.class) && (!FormulaTypeUtils.isTypeText(clazz2)) && (clazz2 != RuntimeType.class))
                throw new WrongArgumentTypeException(node.getText(), new Type[] { clazz, clazz2 }, toConvert);

            if (clazz2 == RuntimeType.class) {
                resultType = clazz2;
            }
        } else if (kids > 2) {
            throw new WrongNumberOfArgumentsException(node.getText(), 1, node);
        }

        return resultType;
    }
    
    @Override
    public JsValue getJavascript(FormulaAST node, FormulaContext context, JsValue[] args) throws FormulaException {
        throw new UnsupportedOperationException();
    }
}

class FunctionFormatCommand extends AbstractFormulaCommand {
    private static final long serialVersionUID = 1L;
    private final int numNodes;
    public FunctionFormatCommand(FormulaCommandInfo info, int numNodes) {
        super(info);
        this.numNodes = numNodes;
    }

    @Override
    public void execute(FormulaRuntimeContext context, Deque<Object> stack) {
        // Pop off all the arguments
        Object[] args = new Object[numNodes - 1];
        for (int i = 1; i < numNodes; i++) {
            Object value = stack.pop();
            args[numNodes - i - 1] = value == null ? "" : value;
        }

        Object first = stack.pop();

        boolean isStringFormat = numNodes > 0 && first instanceof String;
        if (isStringFormat) {
            FormulaValidationHooks hooks = FormulaValidationHooks.get();
            
            // Convert dates
            for (int i = 0; i < args.length; i++) {
                Object value = args[i];
                if (value instanceof FormulaDateTime) {
                    args[i] = ((FormulaDateTime)value).getDate();
                } else if (value instanceof FormulaTime) {
                    args[i] = new Date(((FormulaTime)value).getTimeInMillis());
                } else {
                    args[i] = hooks.convertToString(args[i]);
                }
            }
            String pattern = hooks.validateMessageFormat((String)first, args);

            // Try to set the locale and timezone for 
            try {
                BaseLocalizer localizer = hooks.getLocalizer();
                MessageFormat mf = new MessageFormat(pattern, localizer.getLocale());
                Object [] formats = mf.getFormats();
                for (int i = 0; i < formats.length; i++) {
                    if (formats[i] instanceof DateFormat) {
                        ((DateFormat)formats[i]).setTimeZone(hooks.getLocalizer().getTimeZone());
                    }
                }
                String result = mf.format(args);
                stack.push(result);
            } catch (IllegalArgumentException ex) {
                throw new FormulaEvaluationException(ex);
            }
            return;
        }

        // If it's a label reference, the arguments are supposed to be renameable, and provided from a formula context value or another
        // implementation specific function (as renameable entities are very implementation specific)
        if (first instanceof LabelReference) {
            Renameable[] renameable = new Renameable[args.length];
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Renameable) {
                    renameable[i] = (Renameable) args[i];
                }
                // If the object isn't renameable, just ignore it for now.
            }
            stack.push(FormulaI18nUtils.renderLabelReference((LabelReference)first, renameable));
            return;        	
        }

        if (numNodes > 2) {
            // usually caught at compile time, but not if first arg is RuntimeTyped
            throw new FormulaEvaluationException(FormulaI18nUtils.getLocalizer().getLabel("FormulaFieldExceptionMessages", WrongNumberOfArgumentsException.class.getName(),
                WrongArgumentTypeException.getDescription(getName()), 1, numNodes));
        }

        String format = null;
        if (numNodes > 1) format = checkStringType(args[0]);

        String result;

        if (first == null) {
            result = null;
        } else if (first instanceof Date || first instanceof FormulaDateTime){
            Date d = first instanceof Date ? (Date)first : ((FormulaDateTime)first).getDate();
            if (format == null) {
                if (first instanceof Date) {
                    result = FormulaI18nUtils.getLocalizer().formatDate(d, BaseLocalizer.GMT);
                } else {
                    result = FormulaI18nUtils.getLocalizer().formatDateTime(d);
                }
            } else {
                try {
                    DateFormat sdf = getFormatter(format);
                    sdf.setTimeZone(FormulaI18nUtils.getLocalizer().getTimeZone());
                    result = sdf.format(d);
                } catch (IllegalArgumentException e) {
                    throw new FormulaEvaluationException(e);
                }
            }
        } else if (first instanceof FormulaTime){
            FormulaTime time = (FormulaTime) first;
            Date d = new Date(time.getTimeInMillis());
            if (format == null) {
                result = FormulaI18nUtils.getLocalizer().getTimeFormat().format(d);
            } else {
                try {
                    DateFormat sdf = getFormatter(format);
                    sdf.setTimeZone(FormulaI18nUtils.getLocalizer().getTimeZone());
                    result = sdf.format(d);
                } catch (IllegalArgumentException e) {
                    throw new FormulaEvaluationException(e);
                }
            }
        } else if (first instanceof BigDecimal){
            BigDecimal d = (BigDecimal) first;
            if (format == null) {
                result = FormulaI18nUtils.getLocalizer().getNumberFormat().format(d);
            } else {
                NumberFormat sdf;
                try {
                    sdf = new DecimalFormat(format);
                } catch (IllegalArgumentException e) {
                    throw new FormulaEvaluationException(e);
                }
                result = sdf.format(d);
            }
        } else {
            throw new FormulaEvaluationException("Illegal type");
        }
        stack.push(result);

    }
   

    private DateFormat getFormatter(String pattern) {
        if (FunctionFormat.LOCAL_TIME.equals(pattern)) {
            return FormulaI18nUtils.getLocalizer().getTimeFormat();
        } else if (FunctionFormat.LOCAL_TIME_CORRECT.equals(pattern)) {
            return FormulaValidationHooks.get().getCorrectShortTimeFormat(FormulaI18nUtils.getLocalizer());
        } else if (FunctionFormat.LOCAL_DATE.equals(pattern)) {
            return FormulaI18nUtils.getLocalizer().getDateFormat(BaseLocalizer.LOCAL);
        } else if (FunctionFormat.LOCAL_LONGDATE.equals(pattern)) {
            return FormulaI18nUtils.getLocalizer().getLongDateFormat();
        } else if (pattern != null && pattern.contains(FunctionFormat.USE_LANGUAGE_PREFIX)) {
            String newPattern = pattern.substring(FunctionFormat.USE_LANGUAGE_PREFIX.length());
            return new SimpleDateFormat(newPattern, FormulaI18nUtils.getLocalizer().getUserLanguage().getLocale());
        } else {
            return new SimpleDateFormat(pattern, FormulaI18nUtils.getLocalizer().getLocale());
        }
    }
}
