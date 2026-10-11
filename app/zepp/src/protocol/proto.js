/*eslint-disable block-scoped-var, id-length, no-control-regex, no-magic-numbers, no-prototype-builtins, no-redeclare, no-shadow, no-var, sort-vars*/
import * as $protobuf from "protobufjs/minimal";

// Common aliases
const $Reader = $protobuf.Reader, $Writer = $protobuf.Writer, $util = $protobuf.util;

// Exported root namespace
const $root = $protobuf.roots["default"] || ($protobuf.roots["default"] = {});

export const snapshotpb = $root.snapshotpb = (() => {

    /**
     * Namespace snapshotpb.
     * @exports snapshotpb
     * @namespace
     */
    const snapshotpb = {};

    snapshotpb.v1 = (function() {

        /**
         * Namespace v1.
         * @memberof snapshotpb
         * @namespace
         */
        const v1 = {};

        v1.DisplayWindow = (function() {

            /**
             * Properties of a DisplayWindow.
             * @memberof snapshotpb.v1
             * @interface IDisplayWindow
             * @property {number|null} [x] DisplayWindow x
             * @property {number|null} [y] DisplayWindow y
             * @property {number|null} [w] DisplayWindow w
             * @property {number|null} [h] DisplayWindow h
             */

            /**
             * Constructs a new DisplayWindow.
             * @memberof snapshotpb.v1
             * @classdesc Represents a DisplayWindow.
             * @implements IDisplayWindow
             * @constructor
             * @param {snapshotpb.v1.IDisplayWindow=} [properties] Properties to set
             */
            function DisplayWindow(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * DisplayWindow x.
             * @member {number} x
             * @memberof snapshotpb.v1.DisplayWindow
             * @instance
             */
            DisplayWindow.prototype.x = 0;

            /**
             * DisplayWindow y.
             * @member {number} y
             * @memberof snapshotpb.v1.DisplayWindow
             * @instance
             */
            DisplayWindow.prototype.y = 0;

            /**
             * DisplayWindow w.
             * @member {number} w
             * @memberof snapshotpb.v1.DisplayWindow
             * @instance
             */
            DisplayWindow.prototype.w = 0;

            /**
             * DisplayWindow h.
             * @member {number} h
             * @memberof snapshotpb.v1.DisplayWindow
             * @instance
             */
            DisplayWindow.prototype.h = 0;

            /**
             * Creates a new DisplayWindow instance using the specified properties.
             * @function create
             * @memberof snapshotpb.v1.DisplayWindow
             * @static
             * @param {snapshotpb.v1.IDisplayWindow=} [properties] Properties to set
             * @returns {snapshotpb.v1.DisplayWindow} DisplayWindow instance
             */
            DisplayWindow.create = function create(properties) {
                return new DisplayWindow(properties);
            };

            /**
             * Encodes the specified DisplayWindow message. Does not implicitly {@link snapshotpb.v1.DisplayWindow.verify|verify} messages.
             * @function encode
             * @memberof snapshotpb.v1.DisplayWindow
             * @static
             * @param {snapshotpb.v1.IDisplayWindow} message DisplayWindow message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            DisplayWindow.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.x != null && Object.hasOwnProperty.call(message, "x"))
                    writer.uint32(/* id 1, wireType 0 =*/8).uint32(message.x);
                if (message.y != null && Object.hasOwnProperty.call(message, "y"))
                    writer.uint32(/* id 2, wireType 0 =*/16).uint32(message.y);
                if (message.w != null && Object.hasOwnProperty.call(message, "w"))
                    writer.uint32(/* id 3, wireType 0 =*/24).uint32(message.w);
                if (message.h != null && Object.hasOwnProperty.call(message, "h"))
                    writer.uint32(/* id 4, wireType 0 =*/32).uint32(message.h);
                return writer;
            };

            /**
             * Encodes the specified DisplayWindow message, length delimited. Does not implicitly {@link snapshotpb.v1.DisplayWindow.verify|verify} messages.
             * @function encodeDelimited
             * @memberof snapshotpb.v1.DisplayWindow
             * @static
             * @param {snapshotpb.v1.IDisplayWindow} message DisplayWindow message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            DisplayWindow.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a DisplayWindow message from the specified reader or buffer.
             * @function decode
             * @memberof snapshotpb.v1.DisplayWindow
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {snapshotpb.v1.DisplayWindow} DisplayWindow
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            DisplayWindow.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end, message;
                if (length === undefined)
                    end = reader.len;
                else {
                    end = reader.pos + length;
                    if (end > reader.len)
                        throw RangeError("index out of range");
                    length = reader.len;
                    reader.len = end;
                }
                message = new $root.snapshotpb.v1.DisplayWindow();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.x = reader.uint32();
                            break;
                        }
                    case 2: {
                            message.y = reader.uint32();
                            break;
                        }
                    case 3: {
                            message.w = reader.uint32();
                            break;
                        }
                    case 4: {
                            message.h = reader.uint32();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                if (length !== undefined) {
                    if (reader.pos !== end)
                        throw RangeError("index out of range");
                    reader.len = length;
                }
                return message;
            };

            /**
             * Decodes a DisplayWindow message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof snapshotpb.v1.DisplayWindow
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {snapshotpb.v1.DisplayWindow} DisplayWindow
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            DisplayWindow.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a DisplayWindow message.
             * @function verify
             * @memberof snapshotpb.v1.DisplayWindow
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            DisplayWindow.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.x != null && Object.hasOwnProperty.call(message, "x"))
                    if (!$util.isInteger(message.x))
                        return "x: integer expected";
                if (message.y != null && Object.hasOwnProperty.call(message, "y"))
                    if (!$util.isInteger(message.y))
                        return "y: integer expected";
                if (message.w != null && Object.hasOwnProperty.call(message, "w"))
                    if (!$util.isInteger(message.w))
                        return "w: integer expected";
                if (message.h != null && Object.hasOwnProperty.call(message, "h"))
                    if (!$util.isInteger(message.h))
                        return "h: integer expected";
                return null;
            };

            /**
             * Creates a DisplayWindow message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof snapshotpb.v1.DisplayWindow
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {snapshotpb.v1.DisplayWindow} DisplayWindow
             */
            DisplayWindow.fromObject = function fromObject(object, long) {
                if (object instanceof $root.snapshotpb.v1.DisplayWindow)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".snapshotpb.v1.DisplayWindow: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.snapshotpb.v1.DisplayWindow();
                if (object.x != null)
                    message.x = object.x >>> 0;
                if (object.y != null)
                    message.y = object.y >>> 0;
                if (object.w != null)
                    message.w = object.w >>> 0;
                if (object.h != null)
                    message.h = object.h >>> 0;
                return message;
            };

            /**
             * Creates a plain object from a DisplayWindow message. Also converts values to other types if specified.
             * @function toObject
             * @memberof snapshotpb.v1.DisplayWindow
             * @static
             * @param {snapshotpb.v1.DisplayWindow} message DisplayWindow
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            DisplayWindow.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.x = 0;
                    object.y = 0;
                    object.w = 0;
                    object.h = 0;
                }
                if (message.x != null && Object.hasOwnProperty.call(message, "x"))
                    object.x = message.x;
                if (message.y != null && Object.hasOwnProperty.call(message, "y"))
                    object.y = message.y;
                if (message.w != null && Object.hasOwnProperty.call(message, "w"))
                    object.w = message.w;
                if (message.h != null && Object.hasOwnProperty.call(message, "h"))
                    object.h = message.h;
                return object;
            };

            /**
             * Converts this DisplayWindow to JSON.
             * @function toJSON
             * @memberof snapshotpb.v1.DisplayWindow
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            DisplayWindow.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for DisplayWindow
             * @function getTypeUrl
             * @memberof snapshotpb.v1.DisplayWindow
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            DisplayWindow.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/snapshotpb.v1.DisplayWindow";
            };

            return DisplayWindow;
        })();

        /**
         * ScreenBrightness enum.
         * @name snapshotpb.v1.ScreenBrightness
         * @enum {number}
         * @property {number} SCREEN_BRIGHTNESS_UNSPECIFIED=0 SCREEN_BRIGHTNESS_UNSPECIFIED value
         * @property {number} SCREEN_BRIGHTNESS_LOW=1 SCREEN_BRIGHTNESS_LOW value
         * @property {number} SCREEN_BRIGHTNESS_DEFAULT=2 SCREEN_BRIGHTNESS_DEFAULT value
         * @property {number} SCREEN_BRIGHTNESS_HIGH=3 SCREEN_BRIGHTNESS_HIGH value
         */
        v1.ScreenBrightness = (function() {
            const valuesById = {}, values = Object.create(valuesById);
            values[valuesById[0] = "SCREEN_BRIGHTNESS_UNSPECIFIED"] = 0;
            values[valuesById[1] = "SCREEN_BRIGHTNESS_LOW"] = 1;
            values[valuesById[2] = "SCREEN_BRIGHTNESS_DEFAULT"] = 2;
            values[valuesById[3] = "SCREEN_BRIGHTNESS_HIGH"] = 3;
            return values;
        })();

        /**
         * BatteryType enum.
         * @name snapshotpb.v1.BatteryType
         * @enum {number}
         * @property {number} BATTERY_TYPE_UNSPECIFIED=0 BATTERY_TYPE_UNSPECIFIED value
         * @property {number} BATTERY_TYPE_9V=1 BATTERY_TYPE_9V value
         * @property {number} BATTERY_TYPE_2S_LIPO=2 BATTERY_TYPE_2S_LIPO value
         */
        v1.BatteryType = (function() {
            const valuesById = {}, values = Object.create(valuesById);
            values[valuesById[0] = "BATTERY_TYPE_UNSPECIFIED"] = 0;
            values[valuesById[1] = "BATTERY_TYPE_9V"] = 1;
            values[valuesById[2] = "BATTERY_TYPE_2S_LIPO"] = 2;
            return values;
        })();

        v1.DeviceConfig = (function() {

            /**
             * Properties of a DeviceConfig.
             * @memberof snapshotpb.v1
             * @interface IDeviceConfig
             * @property {number|null} [activeProfile] DeviceConfig activeProfile
             * @property {string|null} [bootText] DeviceConfig bootText
             * @property {snapshotpb.v1.IDisplayWindow|null} [displayWindow] DeviceConfig displayWindow
             * @property {snapshotpb.v1.Language|null} [language] DeviceConfig language
             * @property {snapshotpb.v1.ScreenBrightness|null} [screenBrightness] DeviceConfig screenBrightness
             * @property {number|null} [screenOffDelay] DeviceConfig screenOffDelay
             * @property {snapshotpb.v1.BatteryType|null} [batteryType] DeviceConfig batteryType
             */

            /**
             * Constructs a new DeviceConfig.
             * @memberof snapshotpb.v1
             * @classdesc Represents a DeviceConfig.
             * @implements IDeviceConfig
             * @constructor
             * @param {snapshotpb.v1.IDeviceConfig=} [properties] Properties to set
             */
            function DeviceConfig(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * DeviceConfig activeProfile.
             * @member {number} activeProfile
             * @memberof snapshotpb.v1.DeviceConfig
             * @instance
             */
            DeviceConfig.prototype.activeProfile = 0;

            /**
             * DeviceConfig bootText.
             * @member {string} bootText
             * @memberof snapshotpb.v1.DeviceConfig
             * @instance
             */
            DeviceConfig.prototype.bootText = "";

            /**
             * DeviceConfig displayWindow.
             * @member {snapshotpb.v1.IDisplayWindow|null|undefined} displayWindow
             * @memberof snapshotpb.v1.DeviceConfig
             * @instance
             */
            DeviceConfig.prototype.displayWindow = null;

            /**
             * DeviceConfig language.
             * @member {snapshotpb.v1.Language} language
             * @memberof snapshotpb.v1.DeviceConfig
             * @instance
             */
            DeviceConfig.prototype.language = 0;

            /**
             * DeviceConfig screenBrightness.
             * @member {snapshotpb.v1.ScreenBrightness} screenBrightness
             * @memberof snapshotpb.v1.DeviceConfig
             * @instance
             */
            DeviceConfig.prototype.screenBrightness = 0;

            /**
             * DeviceConfig screenOffDelay.
             * @member {number} screenOffDelay
             * @memberof snapshotpb.v1.DeviceConfig
             * @instance
             */
            DeviceConfig.prototype.screenOffDelay = 0;

            /**
             * DeviceConfig batteryType.
             * @member {snapshotpb.v1.BatteryType} batteryType
             * @memberof snapshotpb.v1.DeviceConfig
             * @instance
             */
            DeviceConfig.prototype.batteryType = 0;

            /**
             * Creates a new DeviceConfig instance using the specified properties.
             * @function create
             * @memberof snapshotpb.v1.DeviceConfig
             * @static
             * @param {snapshotpb.v1.IDeviceConfig=} [properties] Properties to set
             * @returns {snapshotpb.v1.DeviceConfig} DeviceConfig instance
             */
            DeviceConfig.create = function create(properties) {
                return new DeviceConfig(properties);
            };

            /**
             * Encodes the specified DeviceConfig message. Does not implicitly {@link snapshotpb.v1.DeviceConfig.verify|verify} messages.
             * @function encode
             * @memberof snapshotpb.v1.DeviceConfig
             * @static
             * @param {snapshotpb.v1.IDeviceConfig} message DeviceConfig message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            DeviceConfig.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.activeProfile != null && Object.hasOwnProperty.call(message, "activeProfile"))
                    writer.uint32(/* id 1, wireType 0 =*/8).uint32(message.activeProfile);
                if (message.bootText != null && Object.hasOwnProperty.call(message, "bootText"))
                    writer.uint32(/* id 2, wireType 2 =*/18).string(message.bootText);
                if (message.displayWindow != null && Object.hasOwnProperty.call(message, "displayWindow"))
                    $root.snapshotpb.v1.DisplayWindow.encode(message.displayWindow, writer.uint32(/* id 3, wireType 2 =*/26).fork(), q + 1).ldelim();
                if (message.language != null && Object.hasOwnProperty.call(message, "language"))
                    writer.uint32(/* id 4, wireType 0 =*/32).int32(message.language);
                if (message.screenBrightness != null && Object.hasOwnProperty.call(message, "screenBrightness"))
                    writer.uint32(/* id 5, wireType 0 =*/40).int32(message.screenBrightness);
                if (message.screenOffDelay != null && Object.hasOwnProperty.call(message, "screenOffDelay"))
                    writer.uint32(/* id 6, wireType 0 =*/48).uint32(message.screenOffDelay);
                if (message.batteryType != null && Object.hasOwnProperty.call(message, "batteryType"))
                    writer.uint32(/* id 7, wireType 0 =*/56).int32(message.batteryType);
                return writer;
            };

            /**
             * Encodes the specified DeviceConfig message, length delimited. Does not implicitly {@link snapshotpb.v1.DeviceConfig.verify|verify} messages.
             * @function encodeDelimited
             * @memberof snapshotpb.v1.DeviceConfig
             * @static
             * @param {snapshotpb.v1.IDeviceConfig} message DeviceConfig message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            DeviceConfig.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a DeviceConfig message from the specified reader or buffer.
             * @function decode
             * @memberof snapshotpb.v1.DeviceConfig
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {snapshotpb.v1.DeviceConfig} DeviceConfig
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            DeviceConfig.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end, message;
                if (length === undefined)
                    end = reader.len;
                else {
                    end = reader.pos + length;
                    if (end > reader.len)
                        throw RangeError("index out of range");
                    length = reader.len;
                    reader.len = end;
                }
                message = new $root.snapshotpb.v1.DeviceConfig();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.activeProfile = reader.uint32();
                            break;
                        }
                    case 2: {
                            message.bootText = reader.string();
                            break;
                        }
                    case 3: {
                            message.displayWindow = $root.snapshotpb.v1.DisplayWindow.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    case 4: {
                            message.language = reader.int32();
                            break;
                        }
                    case 5: {
                            message.screenBrightness = reader.int32();
                            break;
                        }
                    case 6: {
                            message.screenOffDelay = reader.uint32();
                            break;
                        }
                    case 7: {
                            message.batteryType = reader.int32();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                if (length !== undefined) {
                    if (reader.pos !== end)
                        throw RangeError("index out of range");
                    reader.len = length;
                }
                return message;
            };

            /**
             * Decodes a DeviceConfig message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof snapshotpb.v1.DeviceConfig
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {snapshotpb.v1.DeviceConfig} DeviceConfig
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            DeviceConfig.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a DeviceConfig message.
             * @function verify
             * @memberof snapshotpb.v1.DeviceConfig
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            DeviceConfig.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.activeProfile != null && Object.hasOwnProperty.call(message, "activeProfile"))
                    if (!$util.isInteger(message.activeProfile))
                        return "activeProfile: integer expected";
                if (message.bootText != null && Object.hasOwnProperty.call(message, "bootText"))
                    if (!$util.isString(message.bootText))
                        return "bootText: string expected";
                if (message.displayWindow != null && Object.hasOwnProperty.call(message, "displayWindow")) {
                    let error = $root.snapshotpb.v1.DisplayWindow.verify(message.displayWindow, long + 1);
                    if (error)
                        return "displayWindow." + error;
                }
                if (message.language != null && Object.hasOwnProperty.call(message, "language"))
                    switch (message.language) {
                    default:
                        return "language: enum value expected";
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                        break;
                    }
                if (message.screenBrightness != null && Object.hasOwnProperty.call(message, "screenBrightness"))
                    switch (message.screenBrightness) {
                    default:
                        return "screenBrightness: enum value expected";
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                        break;
                    }
                if (message.screenOffDelay != null && Object.hasOwnProperty.call(message, "screenOffDelay"))
                    if (!$util.isInteger(message.screenOffDelay))
                        return "screenOffDelay: integer expected";
                if (message.batteryType != null && Object.hasOwnProperty.call(message, "batteryType"))
                    switch (message.batteryType) {
                    default:
                        return "batteryType: enum value expected";
                    case 0:
                    case 1:
                    case 2:
                        break;
                    }
                return null;
            };

            /**
             * Creates a DeviceConfig message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof snapshotpb.v1.DeviceConfig
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {snapshotpb.v1.DeviceConfig} DeviceConfig
             */
            DeviceConfig.fromObject = function fromObject(object, long) {
                if (object instanceof $root.snapshotpb.v1.DeviceConfig)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".snapshotpb.v1.DeviceConfig: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.snapshotpb.v1.DeviceConfig();
                if (object.activeProfile != null)
                    message.activeProfile = object.activeProfile >>> 0;
                if (object.bootText != null)
                    message.bootText = String(object.bootText);
                if (object.displayWindow != null) {
                    if (!$util.isObject(object.displayWindow))
                        throw TypeError(".snapshotpb.v1.DeviceConfig.displayWindow: object expected");
                    message.displayWindow = $root.snapshotpb.v1.DisplayWindow.fromObject(object.displayWindow, long + 1);
                }
                switch (object.language) {
                default:
                    if (typeof object.language === "number") {
                        message.language = object.language;
                        break;
                    }
                    break;
                case "LANGUAGE_UNSPECIFIED":
                case 0:
                    message.language = 0;
                    break;
                case "LANGUAGE_EN":
                case 1:
                    message.language = 1;
                    break;
                case "LANGUAGE_ES":
                case 2:
                    message.language = 2;
                    break;
                case "LANGUAGE_DE":
                case 3:
                    message.language = 3;
                    break;
                case "LANGUAGE_FR":
                case 4:
                    message.language = 4;
                    break;
                }
                switch (object.screenBrightness) {
                default:
                    if (typeof object.screenBrightness === "number") {
                        message.screenBrightness = object.screenBrightness;
                        break;
                    }
                    break;
                case "SCREEN_BRIGHTNESS_UNSPECIFIED":
                case 0:
                    message.screenBrightness = 0;
                    break;
                case "SCREEN_BRIGHTNESS_LOW":
                case 1:
                    message.screenBrightness = 1;
                    break;
                case "SCREEN_BRIGHTNESS_DEFAULT":
                case 2:
                    message.screenBrightness = 2;
                    break;
                case "SCREEN_BRIGHTNESS_HIGH":
                case 3:
                    message.screenBrightness = 3;
                    break;
                }
                if (object.screenOffDelay != null)
                    message.screenOffDelay = object.screenOffDelay >>> 0;
                switch (object.batteryType) {
                default:
                    if (typeof object.batteryType === "number") {
                        message.batteryType = object.batteryType;
                        break;
                    }
                    break;
                case "BATTERY_TYPE_UNSPECIFIED":
                case 0:
                    message.batteryType = 0;
                    break;
                case "BATTERY_TYPE_9V":
                case 1:
                    message.batteryType = 1;
                    break;
                case "BATTERY_TYPE_2S_LIPO":
                case 2:
                    message.batteryType = 2;
                    break;
                }
                return message;
            };

            /**
             * Creates a plain object from a DeviceConfig message. Also converts values to other types if specified.
             * @function toObject
             * @memberof snapshotpb.v1.DeviceConfig
             * @static
             * @param {snapshotpb.v1.DeviceConfig} message DeviceConfig
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            DeviceConfig.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.activeProfile = 0;
                    object.bootText = "";
                    object.displayWindow = null;
                    object.language = options.enums === String ? "LANGUAGE_UNSPECIFIED" : 0;
                    object.screenBrightness = options.enums === String ? "SCREEN_BRIGHTNESS_UNSPECIFIED" : 0;
                    object.screenOffDelay = 0;
                    object.batteryType = options.enums === String ? "BATTERY_TYPE_UNSPECIFIED" : 0;
                }
                if (message.activeProfile != null && Object.hasOwnProperty.call(message, "activeProfile"))
                    object.activeProfile = message.activeProfile;
                if (message.bootText != null && Object.hasOwnProperty.call(message, "bootText"))
                    object.bootText = message.bootText;
                if (message.displayWindow != null && Object.hasOwnProperty.call(message, "displayWindow"))
                    object.displayWindow = $root.snapshotpb.v1.DisplayWindow.toObject(message.displayWindow, options, q + 1);
                if (message.language != null && Object.hasOwnProperty.call(message, "language"))
                    object.language = options.enums === String ? $root.snapshotpb.v1.Language[message.language] === undefined ? message.language : $root.snapshotpb.v1.Language[message.language] : message.language;
                if (message.screenBrightness != null && Object.hasOwnProperty.call(message, "screenBrightness"))
                    object.screenBrightness = options.enums === String ? $root.snapshotpb.v1.ScreenBrightness[message.screenBrightness] === undefined ? message.screenBrightness : $root.snapshotpb.v1.ScreenBrightness[message.screenBrightness] : message.screenBrightness;
                if (message.screenOffDelay != null && Object.hasOwnProperty.call(message, "screenOffDelay"))
                    object.screenOffDelay = message.screenOffDelay;
                if (message.batteryType != null && Object.hasOwnProperty.call(message, "batteryType"))
                    object.batteryType = options.enums === String ? $root.snapshotpb.v1.BatteryType[message.batteryType] === undefined ? message.batteryType : $root.snapshotpb.v1.BatteryType[message.batteryType] : message.batteryType;
                return object;
            };

            /**
             * Converts this DeviceConfig to JSON.
             * @function toJSON
             * @memberof snapshotpb.v1.DeviceConfig
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            DeviceConfig.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for DeviceConfig
             * @function getTypeUrl
             * @memberof snapshotpb.v1.DeviceConfig
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            DeviceConfig.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/snapshotpb.v1.DeviceConfig";
            };

            return DeviceConfig;
        })();

        /**
         * Language enum.
         * @name snapshotpb.v1.Language
         * @enum {number}
         * @property {number} LANGUAGE_UNSPECIFIED=0 LANGUAGE_UNSPECIFIED value
         * @property {number} LANGUAGE_EN=1 LANGUAGE_EN value
         * @property {number} LANGUAGE_ES=2 LANGUAGE_ES value
         * @property {number} LANGUAGE_DE=3 LANGUAGE_DE value
         * @property {number} LANGUAGE_FR=4 LANGUAGE_FR value
         */
        v1.Language = (function() {
            const valuesById = {}, values = Object.create(valuesById);
            values[valuesById[0] = "LANGUAGE_UNSPECIFIED"] = 0;
            values[valuesById[1] = "LANGUAGE_EN"] = 1;
            values[valuesById[2] = "LANGUAGE_ES"] = 2;
            values[valuesById[3] = "LANGUAGE_DE"] = 3;
            values[valuesById[4] = "LANGUAGE_FR"] = 4;
            return values;
        })();

        /**
         * BoardModel enum.
         * @name snapshotpb.v1.BoardModel
         * @enum {number}
         * @property {number} BOARD_MODEL_UNSPECIFIED=0 BOARD_MODEL_UNSPECIFIED value
         * @property {number} BOARD_MODEL_AUTOCOCKER=1 BOARD_MODEL_AUTOCOCKER value
         */
        v1.BoardModel = (function() {
            const valuesById = {}, values = Object.create(valuesById);
            values[valuesById[0] = "BOARD_MODEL_UNSPECIFIED"] = 0;
            values[valuesById[1] = "BOARD_MODEL_AUTOCOCKER"] = 1;
            return values;
        })();

        v1.Device = (function() {

            /**
             * Properties of a Device.
             * @memberof snapshotpb.v1
             * @interface IDevice
             * @property {Uint8Array|null} [deviceId] Device deviceId
             * @property {boolean|null} [lockoutEnabled] Device lockoutEnabled
             * @property {snapshotpb.v1.BoardModel|null} [model] Device model
             * @property {number|null} [supportedProfileCount] Device supportedProfileCount
             */

            /**
             * Constructs a new Device.
             * @memberof snapshotpb.v1
             * @classdesc Represents a Device.
             * @implements IDevice
             * @constructor
             * @param {snapshotpb.v1.IDevice=} [properties] Properties to set
             */
            function Device(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * Device deviceId.
             * @member {Uint8Array} deviceId
             * @memberof snapshotpb.v1.Device
             * @instance
             */
            Device.prototype.deviceId = $util.newBuffer([]);

            /**
             * Device lockoutEnabled.
             * @member {boolean} lockoutEnabled
             * @memberof snapshotpb.v1.Device
             * @instance
             */
            Device.prototype.lockoutEnabled = false;

            /**
             * Device model.
             * @member {snapshotpb.v1.BoardModel} model
             * @memberof snapshotpb.v1.Device
             * @instance
             */
            Device.prototype.model = 0;

            /**
             * Device supportedProfileCount.
             * @member {number} supportedProfileCount
             * @memberof snapshotpb.v1.Device
             * @instance
             */
            Device.prototype.supportedProfileCount = 0;

            /**
             * Creates a new Device instance using the specified properties.
             * @function create
             * @memberof snapshotpb.v1.Device
             * @static
             * @param {snapshotpb.v1.IDevice=} [properties] Properties to set
             * @returns {snapshotpb.v1.Device} Device instance
             */
            Device.create = function create(properties) {
                return new Device(properties);
            };

            /**
             * Encodes the specified Device message. Does not implicitly {@link snapshotpb.v1.Device.verify|verify} messages.
             * @function encode
             * @memberof snapshotpb.v1.Device
             * @static
             * @param {snapshotpb.v1.IDevice} message Device message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            Device.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.deviceId != null && Object.hasOwnProperty.call(message, "deviceId"))
                    writer.uint32(/* id 1, wireType 2 =*/10).bytes(message.deviceId);
                if (message.lockoutEnabled != null && Object.hasOwnProperty.call(message, "lockoutEnabled"))
                    writer.uint32(/* id 2, wireType 0 =*/16).bool(message.lockoutEnabled);
                if (message.model != null && Object.hasOwnProperty.call(message, "model"))
                    writer.uint32(/* id 3, wireType 0 =*/24).int32(message.model);
                if (message.supportedProfileCount != null && Object.hasOwnProperty.call(message, "supportedProfileCount"))
                    writer.uint32(/* id 4, wireType 0 =*/32).uint32(message.supportedProfileCount);
                return writer;
            };

            /**
             * Encodes the specified Device message, length delimited. Does not implicitly {@link snapshotpb.v1.Device.verify|verify} messages.
             * @function encodeDelimited
             * @memberof snapshotpb.v1.Device
             * @static
             * @param {snapshotpb.v1.IDevice} message Device message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            Device.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a Device message from the specified reader or buffer.
             * @function decode
             * @memberof snapshotpb.v1.Device
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {snapshotpb.v1.Device} Device
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            Device.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end, message;
                if (length === undefined)
                    end = reader.len;
                else {
                    end = reader.pos + length;
                    if (end > reader.len)
                        throw RangeError("index out of range");
                    length = reader.len;
                    reader.len = end;
                }
                message = new $root.snapshotpb.v1.Device();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.deviceId = reader.bytes();
                            break;
                        }
                    case 2: {
                            message.lockoutEnabled = reader.bool();
                            break;
                        }
                    case 3: {
                            message.model = reader.int32();
                            break;
                        }
                    case 4: {
                            message.supportedProfileCount = reader.uint32();
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                if (length !== undefined) {
                    if (reader.pos !== end)
                        throw RangeError("index out of range");
                    reader.len = length;
                }
                return message;
            };

            /**
             * Decodes a Device message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof snapshotpb.v1.Device
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {snapshotpb.v1.Device} Device
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            Device.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a Device message.
             * @function verify
             * @memberof snapshotpb.v1.Device
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            Device.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.deviceId != null && Object.hasOwnProperty.call(message, "deviceId"))
                    if (!(message.deviceId && typeof message.deviceId.length === "number" || $util.isString(message.deviceId)))
                        return "deviceId: buffer expected";
                if (message.lockoutEnabled != null && Object.hasOwnProperty.call(message, "lockoutEnabled"))
                    if (typeof message.lockoutEnabled !== "boolean")
                        return "lockoutEnabled: boolean expected";
                if (message.model != null && Object.hasOwnProperty.call(message, "model"))
                    switch (message.model) {
                    default:
                        return "model: enum value expected";
                    case 0:
                    case 1:
                        break;
                    }
                if (message.supportedProfileCount != null && Object.hasOwnProperty.call(message, "supportedProfileCount"))
                    if (!$util.isInteger(message.supportedProfileCount))
                        return "supportedProfileCount: integer expected";
                return null;
            };

            /**
             * Creates a Device message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof snapshotpb.v1.Device
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {snapshotpb.v1.Device} Device
             */
            Device.fromObject = function fromObject(object, long) {
                if (object instanceof $root.snapshotpb.v1.Device)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".snapshotpb.v1.Device: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.snapshotpb.v1.Device();
                if (object.deviceId != null)
                    if (typeof object.deviceId === "string")
                        $util.base64.decode(object.deviceId, message.deviceId = $util.newBuffer($util.base64.length(object.deviceId)), 0);
                    else if (object.deviceId.length >= 0)
                        message.deviceId = object.deviceId;
                if (object.lockoutEnabled != null)
                    message.lockoutEnabled = Boolean(object.lockoutEnabled);
                switch (object.model) {
                default:
                    if (typeof object.model === "number") {
                        message.model = object.model;
                        break;
                    }
                    break;
                case "BOARD_MODEL_UNSPECIFIED":
                case 0:
                    message.model = 0;
                    break;
                case "BOARD_MODEL_AUTOCOCKER":
                case 1:
                    message.model = 1;
                    break;
                }
                if (object.supportedProfileCount != null)
                    message.supportedProfileCount = object.supportedProfileCount >>> 0;
                return message;
            };

            /**
             * Creates a plain object from a Device message. Also converts values to other types if specified.
             * @function toObject
             * @memberof snapshotpb.v1.Device
             * @static
             * @param {snapshotpb.v1.Device} message Device
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            Device.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    if (options.bytes === String)
                        object.deviceId = "";
                    else {
                        object.deviceId = [];
                        if (options.bytes !== Array)
                            object.deviceId = $util.newBuffer(object.deviceId);
                    }
                    object.lockoutEnabled = false;
                    object.model = options.enums === String ? "BOARD_MODEL_UNSPECIFIED" : 0;
                    object.supportedProfileCount = 0;
                }
                if (message.deviceId != null && Object.hasOwnProperty.call(message, "deviceId"))
                    object.deviceId = options.bytes === String ? $util.base64.encode(message.deviceId, 0, message.deviceId.length) : options.bytes === Array ? Array.prototype.slice.call(message.deviceId) : message.deviceId;
                if (message.lockoutEnabled != null && Object.hasOwnProperty.call(message, "lockoutEnabled"))
                    object.lockoutEnabled = message.lockoutEnabled;
                if (message.model != null && Object.hasOwnProperty.call(message, "model"))
                    object.model = options.enums === String ? $root.snapshotpb.v1.BoardModel[message.model] === undefined ? message.model : $root.snapshotpb.v1.BoardModel[message.model] : message.model;
                if (message.supportedProfileCount != null && Object.hasOwnProperty.call(message, "supportedProfileCount"))
                    object.supportedProfileCount = message.supportedProfileCount;
                return object;
            };

            /**
             * Converts this Device to JSON.
             * @function toJSON
             * @memberof snapshotpb.v1.Device
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            Device.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for Device
             * @function getTypeUrl
             * @memberof snapshotpb.v1.Device
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            Device.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/snapshotpb.v1.Device";
            };

            return Device;
        })();

        v1.Profile = (function() {

            /**
             * Properties of a Profile.
             * @memberof snapshotpb.v1
             * @interface IProfile
             * @property {string|null} [name] Profile name
             * @property {number|null} [fireRateCap] Profile fireRateCap
             * @property {number|null} [boardAutoOff] Profile boardAutoOff
             * @property {snapshotpb.autococker.v1.IAutocockerConfig|null} [autococker] Profile autococker
             */

            /**
             * Constructs a new Profile.
             * @memberof snapshotpb.v1
             * @classdesc Represents a Profile.
             * @implements IProfile
             * @constructor
             * @param {snapshotpb.v1.IProfile=} [properties] Properties to set
             */
            function Profile(properties) {
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * Profile name.
             * @member {string} name
             * @memberof snapshotpb.v1.Profile
             * @instance
             */
            Profile.prototype.name = "";

            /**
             * Profile fireRateCap.
             * @member {number} fireRateCap
             * @memberof snapshotpb.v1.Profile
             * @instance
             */
            Profile.prototype.fireRateCap = 0;

            /**
             * Profile boardAutoOff.
             * @member {number} boardAutoOff
             * @memberof snapshotpb.v1.Profile
             * @instance
             */
            Profile.prototype.boardAutoOff = 0;

            /**
             * Profile autococker.
             * @member {snapshotpb.autococker.v1.IAutocockerConfig|null|undefined} autococker
             * @memberof snapshotpb.v1.Profile
             * @instance
             */
            Profile.prototype.autococker = null;

            // OneOf field names bound to virtual getters and setters
            let $oneOfFields;

            /**
             * Profile boardConfig.
             * @member {"autococker"|undefined} boardConfig
             * @memberof snapshotpb.v1.Profile
             * @instance
             */
            Object.defineProperty(Profile.prototype, "boardConfig", {
                get: $util.oneOfGetter($oneOfFields = ["autococker"]),
                set: $util.oneOfSetter($oneOfFields)
            });

            /**
             * Creates a new Profile instance using the specified properties.
             * @function create
             * @memberof snapshotpb.v1.Profile
             * @static
             * @param {snapshotpb.v1.IProfile=} [properties] Properties to set
             * @returns {snapshotpb.v1.Profile} Profile instance
             */
            Profile.create = function create(properties) {
                return new Profile(properties);
            };

            /**
             * Encodes the specified Profile message. Does not implicitly {@link snapshotpb.v1.Profile.verify|verify} messages.
             * @function encode
             * @memberof snapshotpb.v1.Profile
             * @static
             * @param {snapshotpb.v1.IProfile} message Profile message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            Profile.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.name != null && Object.hasOwnProperty.call(message, "name"))
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.name);
                if (message.fireRateCap != null && Object.hasOwnProperty.call(message, "fireRateCap"))
                    writer.uint32(/* id 3, wireType 0 =*/24).uint32(message.fireRateCap);
                if (message.boardAutoOff != null && Object.hasOwnProperty.call(message, "boardAutoOff"))
                    writer.uint32(/* id 4, wireType 0 =*/32).uint32(message.boardAutoOff);
                if (message.autococker != null && Object.hasOwnProperty.call(message, "autococker"))
                    $root.snapshotpb.autococker.v1.AutocockerConfig.encode(message.autococker, writer.uint32(/* id 6, wireType 2 =*/50).fork(), q + 1).ldelim();
                return writer;
            };

            /**
             * Encodes the specified Profile message, length delimited. Does not implicitly {@link snapshotpb.v1.Profile.verify|verify} messages.
             * @function encodeDelimited
             * @memberof snapshotpb.v1.Profile
             * @static
             * @param {snapshotpb.v1.IProfile} message Profile message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            Profile.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a Profile message from the specified reader or buffer.
             * @function decode
             * @memberof snapshotpb.v1.Profile
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {snapshotpb.v1.Profile} Profile
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            Profile.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end, message;
                if (length === undefined)
                    end = reader.len;
                else {
                    end = reader.pos + length;
                    if (end > reader.len)
                        throw RangeError("index out of range");
                    length = reader.len;
                    reader.len = end;
                }
                message = new $root.snapshotpb.v1.Profile();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            message.name = reader.string();
                            break;
                        }
                    case 3: {
                            message.fireRateCap = reader.uint32();
                            break;
                        }
                    case 4: {
                            message.boardAutoOff = reader.uint32();
                            break;
                        }
                    case 6: {
                            message.autococker = $root.snapshotpb.autococker.v1.AutocockerConfig.decode(reader, reader.uint32(), undefined, long + 1);
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                if (length !== undefined) {
                    if (reader.pos !== end)
                        throw RangeError("index out of range");
                    reader.len = length;
                }
                return message;
            };

            /**
             * Decodes a Profile message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof snapshotpb.v1.Profile
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {snapshotpb.v1.Profile} Profile
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            Profile.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a Profile message.
             * @function verify
             * @memberof snapshotpb.v1.Profile
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            Profile.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                let properties = {};
                if (message.name != null && Object.hasOwnProperty.call(message, "name"))
                    if (!$util.isString(message.name))
                        return "name: string expected";
                if (message.fireRateCap != null && Object.hasOwnProperty.call(message, "fireRateCap"))
                    if (!$util.isInteger(message.fireRateCap))
                        return "fireRateCap: integer expected";
                if (message.boardAutoOff != null && Object.hasOwnProperty.call(message, "boardAutoOff"))
                    if (!$util.isInteger(message.boardAutoOff))
                        return "boardAutoOff: integer expected";
                if (message.autococker != null && Object.hasOwnProperty.call(message, "autococker")) {
                    properties.boardConfig = 1;
                    {
                        let error = $root.snapshotpb.autococker.v1.AutocockerConfig.verify(message.autococker, long + 1);
                        if (error)
                            return "autococker." + error;
                    }
                }
                return null;
            };

            /**
             * Creates a Profile message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof snapshotpb.v1.Profile
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {snapshotpb.v1.Profile} Profile
             */
            Profile.fromObject = function fromObject(object, long) {
                if (object instanceof $root.snapshotpb.v1.Profile)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".snapshotpb.v1.Profile: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.snapshotpb.v1.Profile();
                if (object.name != null)
                    message.name = String(object.name);
                if (object.fireRateCap != null)
                    message.fireRateCap = object.fireRateCap >>> 0;
                if (object.boardAutoOff != null)
                    message.boardAutoOff = object.boardAutoOff >>> 0;
                if (object.autococker != null) {
                    if (!$util.isObject(object.autococker))
                        throw TypeError(".snapshotpb.v1.Profile.autococker: object expected");
                    message.autococker = $root.snapshotpb.autococker.v1.AutocockerConfig.fromObject(object.autococker, long + 1);
                }
                return message;
            };

            /**
             * Creates a plain object from a Profile message. Also converts values to other types if specified.
             * @function toObject
             * @memberof snapshotpb.v1.Profile
             * @static
             * @param {snapshotpb.v1.Profile} message Profile
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            Profile.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.name = "";
                    object.fireRateCap = 0;
                    object.boardAutoOff = 0;
                }
                if (message.name != null && Object.hasOwnProperty.call(message, "name"))
                    object.name = message.name;
                if (message.fireRateCap != null && Object.hasOwnProperty.call(message, "fireRateCap"))
                    object.fireRateCap = message.fireRateCap;
                if (message.boardAutoOff != null && Object.hasOwnProperty.call(message, "boardAutoOff"))
                    object.boardAutoOff = message.boardAutoOff;
                if (message.autococker != null && Object.hasOwnProperty.call(message, "autococker")) {
                    object.autococker = $root.snapshotpb.autococker.v1.AutocockerConfig.toObject(message.autococker, options, q + 1);
                    if (options.oneofs)
                        object.boardConfig = "autococker";
                }
                return object;
            };

            /**
             * Converts this Profile to JSON.
             * @function toJSON
             * @memberof snapshotpb.v1.Profile
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            Profile.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for Profile
             * @function getTypeUrl
             * @memberof snapshotpb.v1.Profile
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            Profile.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/snapshotpb.v1.Profile";
            };

            return Profile;
        })();

        v1.Profiles = (function() {

            /**
             * Properties of a Profiles.
             * @memberof snapshotpb.v1
             * @interface IProfiles
             * @property {Array.<snapshotpb.v1.IProfile>|null} [profiles] Profiles profiles
             */

            /**
             * Constructs a new Profiles.
             * @memberof snapshotpb.v1
             * @classdesc Represents a Profiles.
             * @implements IProfiles
             * @constructor
             * @param {snapshotpb.v1.IProfiles=} [properties] Properties to set
             */
            function Profiles(properties) {
                this.profiles = [];
                if (properties)
                    for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            }

            /**
             * Profiles profiles.
             * @member {Array.<snapshotpb.v1.IProfile>} profiles
             * @memberof snapshotpb.v1.Profiles
             * @instance
             */
            Profiles.prototype.profiles = $util.emptyArray;

            /**
             * Creates a new Profiles instance using the specified properties.
             * @function create
             * @memberof snapshotpb.v1.Profiles
             * @static
             * @param {snapshotpb.v1.IProfiles=} [properties] Properties to set
             * @returns {snapshotpb.v1.Profiles} Profiles instance
             */
            Profiles.create = function create(properties) {
                return new Profiles(properties);
            };

            /**
             * Encodes the specified Profiles message. Does not implicitly {@link snapshotpb.v1.Profiles.verify|verify} messages.
             * @function encode
             * @memberof snapshotpb.v1.Profiles
             * @static
             * @param {snapshotpb.v1.IProfiles} message Profiles message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            Profiles.encode = function encode(message, writer, q) {
                if (!writer)
                    writer = $Writer.create();
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                if (message.profiles != null && message.profiles.length)
                    for (let i = 0; i < message.profiles.length; ++i)
                        $root.snapshotpb.v1.Profile.encode(message.profiles[i], writer.uint32(/* id 1, wireType 2 =*/10).fork(), q + 1).ldelim();
                return writer;
            };

            /**
             * Encodes the specified Profiles message, length delimited. Does not implicitly {@link snapshotpb.v1.Profiles.verify|verify} messages.
             * @function encodeDelimited
             * @memberof snapshotpb.v1.Profiles
             * @static
             * @param {snapshotpb.v1.IProfiles} message Profiles message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            Profiles.encodeDelimited = function encodeDelimited(message, writer) {
                return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
            };

            /**
             * Decodes a Profiles message from the specified reader or buffer.
             * @function decode
             * @memberof snapshotpb.v1.Profiles
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {snapshotpb.v1.Profiles} Profiles
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            Profiles.decode = function decode(reader, length, error, long) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (long === undefined)
                    long = 0;
                if (long > $Reader.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let end, message;
                if (length === undefined)
                    end = reader.len;
                else {
                    end = reader.pos + length;
                    if (end > reader.len)
                        throw RangeError("index out of range");
                    length = reader.len;
                    reader.len = end;
                }
                message = new $root.snapshotpb.v1.Profiles();
                while (reader.pos < end) {
                    let tag = reader.uint32();
                    if (tag === error)
                        break;
                    switch (tag >>> 3) {
                    case 1: {
                            if (!(message.profiles && message.profiles.length))
                                message.profiles = [];
                            message.profiles.push($root.snapshotpb.v1.Profile.decode(reader, reader.uint32(), undefined, long + 1));
                            break;
                        }
                    default:
                        reader.skipType(tag & 7, long);
                        break;
                    }
                }
                if (length !== undefined) {
                    if (reader.pos !== end)
                        throw RangeError("index out of range");
                    reader.len = length;
                }
                return message;
            };

            /**
             * Decodes a Profiles message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof snapshotpb.v1.Profiles
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {snapshotpb.v1.Profiles} Profiles
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            Profiles.decodeDelimited = function decodeDelimited(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a Profiles message.
             * @function verify
             * @memberof snapshotpb.v1.Profiles
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            Profiles.verify = function verify(message, long) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    return "maximum nesting depth exceeded";
                if (message.profiles != null && Object.hasOwnProperty.call(message, "profiles")) {
                    if (!Array.isArray(message.profiles))
                        return "profiles: array expected";
                    for (let i = 0; i < message.profiles.length; ++i) {
                        let error = $root.snapshotpb.v1.Profile.verify(message.profiles[i], long + 1);
                        if (error)
                            return "profiles." + error;
                    }
                }
                return null;
            };

            /**
             * Creates a Profiles message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof snapshotpb.v1.Profiles
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {snapshotpb.v1.Profiles} Profiles
             */
            Profiles.fromObject = function fromObject(object, long) {
                if (object instanceof $root.snapshotpb.v1.Profiles)
                    return object;
                if (!$util.isObject(object))
                    throw TypeError(".snapshotpb.v1.Profiles: object expected");
                if (long === undefined)
                    long = 0;
                if (long > $util.recursionLimit)
                    throw Error("maximum nesting depth exceeded");
                let message = new $root.snapshotpb.v1.Profiles();
                if (object.profiles) {
                    if (!Array.isArray(object.profiles))
                        throw TypeError(".snapshotpb.v1.Profiles.profiles: array expected");
                    message.profiles = [];
                    for (let i = 0; i < object.profiles.length; ++i) {
                        if (!$util.isObject(object.profiles[i]))
                            throw TypeError(".snapshotpb.v1.Profiles.profiles: object expected");
                        message.profiles[i] = $root.snapshotpb.v1.Profile.fromObject(object.profiles[i], long + 1);
                    }
                }
                return message;
            };

            /**
             * Creates a plain object from a Profiles message. Also converts values to other types if specified.
             * @function toObject
             * @memberof snapshotpb.v1.Profiles
             * @static
             * @param {snapshotpb.v1.Profiles} message Profiles
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            Profiles.toObject = function toObject(message, options, q) {
                if (!options)
                    options = {};
                if (q === undefined)
                    q = 0;
                if (q > $util.recursionLimit)
                    throw Error("max depth exceeded");
                let object = {};
                if (options.arrays || options.defaults)
                    object.profiles = [];
                if (message.profiles && message.profiles.length) {
                    object.profiles = [];
                    for (let j = 0; j < message.profiles.length; ++j)
                        object.profiles[j] = $root.snapshotpb.v1.Profile.toObject(message.profiles[j], options, q + 1);
                }
                return object;
            };

            /**
             * Converts this Profiles to JSON.
             * @function toJSON
             * @memberof snapshotpb.v1.Profiles
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            Profiles.prototype.toJSON = function toJSON() {
                return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the default type url for Profiles
             * @function getTypeUrl
             * @memberof snapshotpb.v1.Profiles
             * @static
             * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
             * @returns {string} The default type url
             */
            Profiles.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                if (typeUrlPrefix === undefined) {
                    typeUrlPrefix = "type.googleapis.com";
                }
                return typeUrlPrefix + "/snapshotpb.v1.Profiles";
            };

            return Profiles;
        })();

        return v1;
    })();

    snapshotpb.autococker = (function() {

        /**
         * Namespace autococker.
         * @memberof snapshotpb
         * @namespace
         */
        const autococker = {};

        autococker.v1 = (function() {

            /**
             * Namespace v1.
             * @memberof snapshotpb.autococker
             * @namespace
             */
            const v1 = {};

            v1.AutocockerConfig = (function() {

                /**
                 * Properties of an AutocockerConfig.
                 * @memberof snapshotpb.autococker.v1
                 * @interface IAutocockerConfig
                 * @property {snapshotpb.autococker.v1.AutocockerFireMode|null} [fireMode] AutocockerConfig fireMode
                 * @property {snapshotpb.common.v1.EyeMode|null} [eyeMode] AutocockerConfig eyeMode
                 * @property {number|null} [eyeThreshold] AutocockerConfig eyeThreshold
                 * @property {number|null} [eyeDebounce] AutocockerConfig eyeDebounce
                 * @property {number|null} [searSolenoidDwell] AutocockerConfig searSolenoidDwell
                 * @property {number|null} [cockingSolenoidDelay] AutocockerConfig cockingSolenoidDelay
                 * @property {number|null} [cockingSolenoidDwellOpen] AutocockerConfig cockingSolenoidDwellOpen
                 * @property {number|null} [cockingSolenoidDwellClosed] AutocockerConfig cockingSolenoidDwellClosed
                 * @property {number|null} [rampingCustomMultiplier] AutocockerConfig rampingCustomMultiplier
                 * @property {number|null} [rampingCustomTriggerPulls] AutocockerConfig rampingCustomTriggerPulls
                 * @property {number|null} [rampingCustomRofMinimum] AutocockerConfig rampingCustomRofMinimum
                 * @property {number|null} [triggerDebounceOn] AutocockerConfig triggerDebounceOn
                 * @property {number|null} [triggerDebounceOff] AutocockerConfig triggerDebounceOff
                 * @property {number|null} [cockingSolenoidTimeout] AutocockerConfig cockingSolenoidTimeout
                 */

                /**
                 * Constructs a new AutocockerConfig.
                 * @memberof snapshotpb.autococker.v1
                 * @classdesc Represents an AutocockerConfig.
                 * @implements IAutocockerConfig
                 * @constructor
                 * @param {snapshotpb.autococker.v1.IAutocockerConfig=} [properties] Properties to set
                 */
                function AutocockerConfig(properties) {
                    if (properties)
                        for (let keys = Object.keys(properties), i = 0; i < keys.length; ++i)
                            if (properties[keys[i]] != null && keys[i] !== "__proto__")
                                this[keys[i]] = properties[keys[i]];
                }

                /**
                 * AutocockerConfig fireMode.
                 * @member {snapshotpb.autococker.v1.AutocockerFireMode} fireMode
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.fireMode = 0;

                /**
                 * AutocockerConfig eyeMode.
                 * @member {snapshotpb.common.v1.EyeMode} eyeMode
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.eyeMode = 0;

                /**
                 * AutocockerConfig eyeThreshold.
                 * @member {number} eyeThreshold
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.eyeThreshold = 0;

                /**
                 * AutocockerConfig eyeDebounce.
                 * @member {number} eyeDebounce
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.eyeDebounce = 0;

                /**
                 * AutocockerConfig searSolenoidDwell.
                 * @member {number} searSolenoidDwell
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.searSolenoidDwell = 0;

                /**
                 * AutocockerConfig cockingSolenoidDelay.
                 * @member {number} cockingSolenoidDelay
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.cockingSolenoidDelay = 0;

                /**
                 * AutocockerConfig cockingSolenoidDwellOpen.
                 * @member {number} cockingSolenoidDwellOpen
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.cockingSolenoidDwellOpen = 0;

                /**
                 * AutocockerConfig cockingSolenoidDwellClosed.
                 * @member {number} cockingSolenoidDwellClosed
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.cockingSolenoidDwellClosed = 0;

                /**
                 * AutocockerConfig rampingCustomMultiplier.
                 * @member {number} rampingCustomMultiplier
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.rampingCustomMultiplier = 0;

                /**
                 * AutocockerConfig rampingCustomTriggerPulls.
                 * @member {number} rampingCustomTriggerPulls
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.rampingCustomTriggerPulls = 0;

                /**
                 * AutocockerConfig rampingCustomRofMinimum.
                 * @member {number} rampingCustomRofMinimum
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.rampingCustomRofMinimum = 0;

                /**
                 * AutocockerConfig triggerDebounceOn.
                 * @member {number} triggerDebounceOn
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.triggerDebounceOn = 0;

                /**
                 * AutocockerConfig triggerDebounceOff.
                 * @member {number} triggerDebounceOff
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.triggerDebounceOff = 0;

                /**
                 * AutocockerConfig cockingSolenoidTimeout.
                 * @member {number} cockingSolenoidTimeout
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 */
                AutocockerConfig.prototype.cockingSolenoidTimeout = 0;

                /**
                 * Creates a new AutocockerConfig instance using the specified properties.
                 * @function create
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @static
                 * @param {snapshotpb.autococker.v1.IAutocockerConfig=} [properties] Properties to set
                 * @returns {snapshotpb.autococker.v1.AutocockerConfig} AutocockerConfig instance
                 */
                AutocockerConfig.create = function create(properties) {
                    return new AutocockerConfig(properties);
                };

                /**
                 * Encodes the specified AutocockerConfig message. Does not implicitly {@link snapshotpb.autococker.v1.AutocockerConfig.verify|verify} messages.
                 * @function encode
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @static
                 * @param {snapshotpb.autococker.v1.IAutocockerConfig} message AutocockerConfig message or plain object to encode
                 * @param {$protobuf.Writer} [writer] Writer to encode to
                 * @returns {$protobuf.Writer} Writer
                 */
                AutocockerConfig.encode = function encode(message, writer, q) {
                    if (!writer)
                        writer = $Writer.create();
                    if (q === undefined)
                        q = 0;
                    if (q > $util.recursionLimit)
                        throw Error("max depth exceeded");
                    if (message.fireMode != null && Object.hasOwnProperty.call(message, "fireMode"))
                        writer.uint32(/* id 1, wireType 0 =*/8).int32(message.fireMode);
                    if (message.eyeMode != null && Object.hasOwnProperty.call(message, "eyeMode"))
                        writer.uint32(/* id 2, wireType 0 =*/16).int32(message.eyeMode);
                    if (message.eyeThreshold != null && Object.hasOwnProperty.call(message, "eyeThreshold"))
                        writer.uint32(/* id 3, wireType 0 =*/24).uint32(message.eyeThreshold);
                    if (message.eyeDebounce != null && Object.hasOwnProperty.call(message, "eyeDebounce"))
                        writer.uint32(/* id 4, wireType 0 =*/32).uint32(message.eyeDebounce);
                    if (message.searSolenoidDwell != null && Object.hasOwnProperty.call(message, "searSolenoidDwell"))
                        writer.uint32(/* id 5, wireType 0 =*/40).uint32(message.searSolenoidDwell);
                    if (message.cockingSolenoidDelay != null && Object.hasOwnProperty.call(message, "cockingSolenoidDelay"))
                        writer.uint32(/* id 6, wireType 0 =*/48).uint32(message.cockingSolenoidDelay);
                    if (message.cockingSolenoidDwellOpen != null && Object.hasOwnProperty.call(message, "cockingSolenoidDwellOpen"))
                        writer.uint32(/* id 7, wireType 0 =*/56).uint32(message.cockingSolenoidDwellOpen);
                    if (message.cockingSolenoidDwellClosed != null && Object.hasOwnProperty.call(message, "cockingSolenoidDwellClosed"))
                        writer.uint32(/* id 8, wireType 0 =*/64).uint32(message.cockingSolenoidDwellClosed);
                    if (message.rampingCustomMultiplier != null && Object.hasOwnProperty.call(message, "rampingCustomMultiplier"))
                        writer.uint32(/* id 9, wireType 0 =*/72).uint32(message.rampingCustomMultiplier);
                    if (message.rampingCustomTriggerPulls != null && Object.hasOwnProperty.call(message, "rampingCustomTriggerPulls"))
                        writer.uint32(/* id 10, wireType 0 =*/80).uint32(message.rampingCustomTriggerPulls);
                    if (message.rampingCustomRofMinimum != null && Object.hasOwnProperty.call(message, "rampingCustomRofMinimum"))
                        writer.uint32(/* id 11, wireType 0 =*/88).uint32(message.rampingCustomRofMinimum);
                    if (message.triggerDebounceOn != null && Object.hasOwnProperty.call(message, "triggerDebounceOn"))
                        writer.uint32(/* id 12, wireType 0 =*/96).uint32(message.triggerDebounceOn);
                    if (message.triggerDebounceOff != null && Object.hasOwnProperty.call(message, "triggerDebounceOff"))
                        writer.uint32(/* id 13, wireType 0 =*/104).uint32(message.triggerDebounceOff);
                    if (message.cockingSolenoidTimeout != null && Object.hasOwnProperty.call(message, "cockingSolenoidTimeout"))
                        writer.uint32(/* id 14, wireType 0 =*/112).uint32(message.cockingSolenoidTimeout);
                    return writer;
                };

                /**
                 * Encodes the specified AutocockerConfig message, length delimited. Does not implicitly {@link snapshotpb.autococker.v1.AutocockerConfig.verify|verify} messages.
                 * @function encodeDelimited
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @static
                 * @param {snapshotpb.autococker.v1.IAutocockerConfig} message AutocockerConfig message or plain object to encode
                 * @param {$protobuf.Writer} [writer] Writer to encode to
                 * @returns {$protobuf.Writer} Writer
                 */
                AutocockerConfig.encodeDelimited = function encodeDelimited(message, writer) {
                    return this.encode(message, writer && writer.len ? writer.fork() : writer).ldelim();
                };

                /**
                 * Decodes an AutocockerConfig message from the specified reader or buffer.
                 * @function decode
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @static
                 * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
                 * @param {number} [length] Message length if known beforehand
                 * @returns {snapshotpb.autococker.v1.AutocockerConfig} AutocockerConfig
                 * @throws {Error} If the payload is not a reader or valid buffer
                 * @throws {$protobuf.util.ProtocolError} If required fields are missing
                 */
                AutocockerConfig.decode = function decode(reader, length, error, long) {
                    if (!(reader instanceof $Reader))
                        reader = $Reader.create(reader);
                    if (long === undefined)
                        long = 0;
                    if (long > $Reader.recursionLimit)
                        throw Error("maximum nesting depth exceeded");
                    let end, message;
                    if (length === undefined)
                        end = reader.len;
                    else {
                        end = reader.pos + length;
                        if (end > reader.len)
                            throw RangeError("index out of range");
                        length = reader.len;
                        reader.len = end;
                    }
                    message = new $root.snapshotpb.autococker.v1.AutocockerConfig();
                    while (reader.pos < end) {
                        let tag = reader.uint32();
                        if (tag === error)
                            break;
                        switch (tag >>> 3) {
                        case 1: {
                                message.fireMode = reader.int32();
                                break;
                            }
                        case 2: {
                                message.eyeMode = reader.int32();
                                break;
                            }
                        case 3: {
                                message.eyeThreshold = reader.uint32();
                                break;
                            }
                        case 4: {
                                message.eyeDebounce = reader.uint32();
                                break;
                            }
                        case 5: {
                                message.searSolenoidDwell = reader.uint32();
                                break;
                            }
                        case 6: {
                                message.cockingSolenoidDelay = reader.uint32();
                                break;
                            }
                        case 7: {
                                message.cockingSolenoidDwellOpen = reader.uint32();
                                break;
                            }
                        case 8: {
                                message.cockingSolenoidDwellClosed = reader.uint32();
                                break;
                            }
                        case 9: {
                                message.rampingCustomMultiplier = reader.uint32();
                                break;
                            }
                        case 10: {
                                message.rampingCustomTriggerPulls = reader.uint32();
                                break;
                            }
                        case 11: {
                                message.rampingCustomRofMinimum = reader.uint32();
                                break;
                            }
                        case 12: {
                                message.triggerDebounceOn = reader.uint32();
                                break;
                            }
                        case 13: {
                                message.triggerDebounceOff = reader.uint32();
                                break;
                            }
                        case 14: {
                                message.cockingSolenoidTimeout = reader.uint32();
                                break;
                            }
                        default:
                            reader.skipType(tag & 7, long);
                            break;
                        }
                    }
                    if (length !== undefined) {
                        if (reader.pos !== end)
                            throw RangeError("index out of range");
                        reader.len = length;
                    }
                    return message;
                };

                /**
                 * Decodes an AutocockerConfig message from the specified reader or buffer, length delimited.
                 * @function decodeDelimited
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @static
                 * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
                 * @returns {snapshotpb.autococker.v1.AutocockerConfig} AutocockerConfig
                 * @throws {Error} If the payload is not a reader or valid buffer
                 * @throws {$protobuf.util.ProtocolError} If required fields are missing
                 */
                AutocockerConfig.decodeDelimited = function decodeDelimited(reader) {
                    if (!(reader instanceof $Reader))
                        reader = new $Reader(reader);
                    return this.decode(reader, reader.uint32());
                };

                /**
                 * Verifies an AutocockerConfig message.
                 * @function verify
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @static
                 * @param {Object.<string,*>} message Plain object to verify
                 * @returns {string|null} `null` if valid, otherwise the reason why it is not
                 */
                AutocockerConfig.verify = function verify(message, long) {
                    if (typeof message !== "object" || message === null)
                        return "object expected";
                    if (long === undefined)
                        long = 0;
                    if (long > $util.recursionLimit)
                        return "maximum nesting depth exceeded";
                    if (message.fireMode != null && Object.hasOwnProperty.call(message, "fireMode"))
                        switch (message.fireMode) {
                        default:
                            return "fireMode: enum value expected";
                        case 0:
                        case 1:
                        case 2:
                        case 3:
                        case 4:
                        case 5:
                        case 6:
                            break;
                        }
                    if (message.eyeMode != null && Object.hasOwnProperty.call(message, "eyeMode"))
                        switch (message.eyeMode) {
                        default:
                            return "eyeMode: enum value expected";
                        case 0:
                        case 1:
                        case 2:
                        case 3:
                            break;
                        }
                    if (message.eyeThreshold != null && Object.hasOwnProperty.call(message, "eyeThreshold"))
                        if (!$util.isInteger(message.eyeThreshold))
                            return "eyeThreshold: integer expected";
                    if (message.eyeDebounce != null && Object.hasOwnProperty.call(message, "eyeDebounce"))
                        if (!$util.isInteger(message.eyeDebounce))
                            return "eyeDebounce: integer expected";
                    if (message.searSolenoidDwell != null && Object.hasOwnProperty.call(message, "searSolenoidDwell"))
                        if (!$util.isInteger(message.searSolenoidDwell))
                            return "searSolenoidDwell: integer expected";
                    if (message.cockingSolenoidDelay != null && Object.hasOwnProperty.call(message, "cockingSolenoidDelay"))
                        if (!$util.isInteger(message.cockingSolenoidDelay))
                            return "cockingSolenoidDelay: integer expected";
                    if (message.cockingSolenoidDwellOpen != null && Object.hasOwnProperty.call(message, "cockingSolenoidDwellOpen"))
                        if (!$util.isInteger(message.cockingSolenoidDwellOpen))
                            return "cockingSolenoidDwellOpen: integer expected";
                    if (message.cockingSolenoidDwellClosed != null && Object.hasOwnProperty.call(message, "cockingSolenoidDwellClosed"))
                        if (!$util.isInteger(message.cockingSolenoidDwellClosed))
                            return "cockingSolenoidDwellClosed: integer expected";
                    if (message.rampingCustomMultiplier != null && Object.hasOwnProperty.call(message, "rampingCustomMultiplier"))
                        if (!$util.isInteger(message.rampingCustomMultiplier))
                            return "rampingCustomMultiplier: integer expected";
                    if (message.rampingCustomTriggerPulls != null && Object.hasOwnProperty.call(message, "rampingCustomTriggerPulls"))
                        if (!$util.isInteger(message.rampingCustomTriggerPulls))
                            return "rampingCustomTriggerPulls: integer expected";
                    if (message.rampingCustomRofMinimum != null && Object.hasOwnProperty.call(message, "rampingCustomRofMinimum"))
                        if (!$util.isInteger(message.rampingCustomRofMinimum))
                            return "rampingCustomRofMinimum: integer expected";
                    if (message.triggerDebounceOn != null && Object.hasOwnProperty.call(message, "triggerDebounceOn"))
                        if (!$util.isInteger(message.triggerDebounceOn))
                            return "triggerDebounceOn: integer expected";
                    if (message.triggerDebounceOff != null && Object.hasOwnProperty.call(message, "triggerDebounceOff"))
                        if (!$util.isInteger(message.triggerDebounceOff))
                            return "triggerDebounceOff: integer expected";
                    if (message.cockingSolenoidTimeout != null && Object.hasOwnProperty.call(message, "cockingSolenoidTimeout"))
                        if (!$util.isInteger(message.cockingSolenoidTimeout))
                            return "cockingSolenoidTimeout: integer expected";
                    return null;
                };

                /**
                 * Creates an AutocockerConfig message from a plain object. Also converts values to their respective internal types.
                 * @function fromObject
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @static
                 * @param {Object.<string,*>} object Plain object
                 * @returns {snapshotpb.autococker.v1.AutocockerConfig} AutocockerConfig
                 */
                AutocockerConfig.fromObject = function fromObject(object, long) {
                    if (object instanceof $root.snapshotpb.autococker.v1.AutocockerConfig)
                        return object;
                    if (!$util.isObject(object))
                        throw TypeError(".snapshotpb.autococker.v1.AutocockerConfig: object expected");
                    if (long === undefined)
                        long = 0;
                    if (long > $util.recursionLimit)
                        throw Error("maximum nesting depth exceeded");
                    let message = new $root.snapshotpb.autococker.v1.AutocockerConfig();
                    switch (object.fireMode) {
                    default:
                        if (typeof object.fireMode === "number") {
                            message.fireMode = object.fireMode;
                            break;
                        }
                        break;
                    case "AUTOCOCKER_FIRE_MODE_UNSPECIFIED":
                    case 0:
                        message.fireMode = 0;
                        break;
                    case "AUTOCOCKER_FIRE_MODE_MECHANICAL":
                    case 1:
                        message.fireMode = 1;
                        break;
                    case "AUTOCOCKER_FIRE_MODE_SEMI":
                    case 2:
                        message.fireMode = 2;
                        break;
                    case "AUTOCOCKER_FIRE_MODE_TRIGGER_ONLY":
                    case 3:
                        message.fireMode = 3;
                        break;
                    case "AUTOCOCKER_FIRE_MODE_FULL_AUTO":
                    case 4:
                        message.fireMode = 4;
                        break;
                    case "AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026":
                    case 5:
                        message.fireMode = 5;
                        break;
                    case "AUTOCOCKER_FIRE_MODE_CUSTOM_RAMPING":
                    case 6:
                        message.fireMode = 6;
                        break;
                    }
                    switch (object.eyeMode) {
                    default:
                        if (typeof object.eyeMode === "number") {
                            message.eyeMode = object.eyeMode;
                            break;
                        }
                        break;
                    case "EYE_MODE_UNSPECIFIED":
                    case 0:
                        message.eyeMode = 0;
                        break;
                    case "EYE_MODE_OFF":
                    case 1:
                        message.eyeMode = 1;
                        break;
                    case "EYE_MODE_REFLECTIVE":
                    case 2:
                        message.eyeMode = 2;
                        break;
                    case "EYE_MODE_BREAK_BEAM":
                    case 3:
                        message.eyeMode = 3;
                        break;
                    }
                    if (object.eyeThreshold != null)
                        message.eyeThreshold = object.eyeThreshold >>> 0;
                    if (object.eyeDebounce != null)
                        message.eyeDebounce = object.eyeDebounce >>> 0;
                    if (object.searSolenoidDwell != null)
                        message.searSolenoidDwell = object.searSolenoidDwell >>> 0;
                    if (object.cockingSolenoidDelay != null)
                        message.cockingSolenoidDelay = object.cockingSolenoidDelay >>> 0;
                    if (object.cockingSolenoidDwellOpen != null)
                        message.cockingSolenoidDwellOpen = object.cockingSolenoidDwellOpen >>> 0;
                    if (object.cockingSolenoidDwellClosed != null)
                        message.cockingSolenoidDwellClosed = object.cockingSolenoidDwellClosed >>> 0;
                    if (object.rampingCustomMultiplier != null)
                        message.rampingCustomMultiplier = object.rampingCustomMultiplier >>> 0;
                    if (object.rampingCustomTriggerPulls != null)
                        message.rampingCustomTriggerPulls = object.rampingCustomTriggerPulls >>> 0;
                    if (object.rampingCustomRofMinimum != null)
                        message.rampingCustomRofMinimum = object.rampingCustomRofMinimum >>> 0;
                    if (object.triggerDebounceOn != null)
                        message.triggerDebounceOn = object.triggerDebounceOn >>> 0;
                    if (object.triggerDebounceOff != null)
                        message.triggerDebounceOff = object.triggerDebounceOff >>> 0;
                    if (object.cockingSolenoidTimeout != null)
                        message.cockingSolenoidTimeout = object.cockingSolenoidTimeout >>> 0;
                    return message;
                };

                /**
                 * Creates a plain object from an AutocockerConfig message. Also converts values to other types if specified.
                 * @function toObject
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @static
                 * @param {snapshotpb.autococker.v1.AutocockerConfig} message AutocockerConfig
                 * @param {$protobuf.IConversionOptions} [options] Conversion options
                 * @returns {Object.<string,*>} Plain object
                 */
                AutocockerConfig.toObject = function toObject(message, options, q) {
                    if (!options)
                        options = {};
                    if (q === undefined)
                        q = 0;
                    if (q > $util.recursionLimit)
                        throw Error("max depth exceeded");
                    let object = {};
                    if (options.defaults) {
                        object.fireMode = options.enums === String ? "AUTOCOCKER_FIRE_MODE_UNSPECIFIED" : 0;
                        object.eyeMode = options.enums === String ? "EYE_MODE_UNSPECIFIED" : 0;
                        object.eyeThreshold = 0;
                        object.eyeDebounce = 0;
                        object.searSolenoidDwell = 0;
                        object.cockingSolenoidDelay = 0;
                        object.cockingSolenoidDwellOpen = 0;
                        object.cockingSolenoidDwellClosed = 0;
                        object.rampingCustomMultiplier = 0;
                        object.rampingCustomTriggerPulls = 0;
                        object.rampingCustomRofMinimum = 0;
                        object.triggerDebounceOn = 0;
                        object.triggerDebounceOff = 0;
                        object.cockingSolenoidTimeout = 0;
                    }
                    if (message.fireMode != null && Object.hasOwnProperty.call(message, "fireMode"))
                        object.fireMode = options.enums === String ? $root.snapshotpb.autococker.v1.AutocockerFireMode[message.fireMode] === undefined ? message.fireMode : $root.snapshotpb.autococker.v1.AutocockerFireMode[message.fireMode] : message.fireMode;
                    if (message.eyeMode != null && Object.hasOwnProperty.call(message, "eyeMode"))
                        object.eyeMode = options.enums === String ? $root.snapshotpb.common.v1.EyeMode[message.eyeMode] === undefined ? message.eyeMode : $root.snapshotpb.common.v1.EyeMode[message.eyeMode] : message.eyeMode;
                    if (message.eyeThreshold != null && Object.hasOwnProperty.call(message, "eyeThreshold"))
                        object.eyeThreshold = message.eyeThreshold;
                    if (message.eyeDebounce != null && Object.hasOwnProperty.call(message, "eyeDebounce"))
                        object.eyeDebounce = message.eyeDebounce;
                    if (message.searSolenoidDwell != null && Object.hasOwnProperty.call(message, "searSolenoidDwell"))
                        object.searSolenoidDwell = message.searSolenoidDwell;
                    if (message.cockingSolenoidDelay != null && Object.hasOwnProperty.call(message, "cockingSolenoidDelay"))
                        object.cockingSolenoidDelay = message.cockingSolenoidDelay;
                    if (message.cockingSolenoidDwellOpen != null && Object.hasOwnProperty.call(message, "cockingSolenoidDwellOpen"))
                        object.cockingSolenoidDwellOpen = message.cockingSolenoidDwellOpen;
                    if (message.cockingSolenoidDwellClosed != null && Object.hasOwnProperty.call(message, "cockingSolenoidDwellClosed"))
                        object.cockingSolenoidDwellClosed = message.cockingSolenoidDwellClosed;
                    if (message.rampingCustomMultiplier != null && Object.hasOwnProperty.call(message, "rampingCustomMultiplier"))
                        object.rampingCustomMultiplier = message.rampingCustomMultiplier;
                    if (message.rampingCustomTriggerPulls != null && Object.hasOwnProperty.call(message, "rampingCustomTriggerPulls"))
                        object.rampingCustomTriggerPulls = message.rampingCustomTriggerPulls;
                    if (message.rampingCustomRofMinimum != null && Object.hasOwnProperty.call(message, "rampingCustomRofMinimum"))
                        object.rampingCustomRofMinimum = message.rampingCustomRofMinimum;
                    if (message.triggerDebounceOn != null && Object.hasOwnProperty.call(message, "triggerDebounceOn"))
                        object.triggerDebounceOn = message.triggerDebounceOn;
                    if (message.triggerDebounceOff != null && Object.hasOwnProperty.call(message, "triggerDebounceOff"))
                        object.triggerDebounceOff = message.triggerDebounceOff;
                    if (message.cockingSolenoidTimeout != null && Object.hasOwnProperty.call(message, "cockingSolenoidTimeout"))
                        object.cockingSolenoidTimeout = message.cockingSolenoidTimeout;
                    return object;
                };

                /**
                 * Converts this AutocockerConfig to JSON.
                 * @function toJSON
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @instance
                 * @returns {Object.<string,*>} JSON object
                 */
                AutocockerConfig.prototype.toJSON = function toJSON() {
                    return this.constructor.toObject(this, $protobuf.util.toJSONOptions);
                };

                /**
                 * Gets the default type url for AutocockerConfig
                 * @function getTypeUrl
                 * @memberof snapshotpb.autococker.v1.AutocockerConfig
                 * @static
                 * @param {string} [typeUrlPrefix] your custom typeUrlPrefix(default "type.googleapis.com")
                 * @returns {string} The default type url
                 */
                AutocockerConfig.getTypeUrl = function getTypeUrl(typeUrlPrefix) {
                    if (typeUrlPrefix === undefined) {
                        typeUrlPrefix = "type.googleapis.com";
                    }
                    return typeUrlPrefix + "/snapshotpb.autococker.v1.AutocockerConfig";
                };

                return AutocockerConfig;
            })();

            /**
             * AutocockerFireMode enum.
             * @name snapshotpb.autococker.v1.AutocockerFireMode
             * @enum {number}
             * @property {number} AUTOCOCKER_FIRE_MODE_UNSPECIFIED=0 AUTOCOCKER_FIRE_MODE_UNSPECIFIED value
             * @property {number} AUTOCOCKER_FIRE_MODE_MECHANICAL=1 AUTOCOCKER_FIRE_MODE_MECHANICAL value
             * @property {number} AUTOCOCKER_FIRE_MODE_SEMI=2 AUTOCOCKER_FIRE_MODE_SEMI value
             * @property {number} AUTOCOCKER_FIRE_MODE_TRIGGER_ONLY=3 AUTOCOCKER_FIRE_MODE_TRIGGER_ONLY value
             * @property {number} AUTOCOCKER_FIRE_MODE_FULL_AUTO=4 AUTOCOCKER_FIRE_MODE_FULL_AUTO value
             * @property {number} AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026=5 AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026 value
             * @property {number} AUTOCOCKER_FIRE_MODE_CUSTOM_RAMPING=6 AUTOCOCKER_FIRE_MODE_CUSTOM_RAMPING value
             */
            v1.AutocockerFireMode = (function() {
                const valuesById = {}, values = Object.create(valuesById);
                values[valuesById[0] = "AUTOCOCKER_FIRE_MODE_UNSPECIFIED"] = 0;
                values[valuesById[1] = "AUTOCOCKER_FIRE_MODE_MECHANICAL"] = 1;
                values[valuesById[2] = "AUTOCOCKER_FIRE_MODE_SEMI"] = 2;
                values[valuesById[3] = "AUTOCOCKER_FIRE_MODE_TRIGGER_ONLY"] = 3;
                values[valuesById[4] = "AUTOCOCKER_FIRE_MODE_FULL_AUTO"] = 4;
                values[valuesById[5] = "AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026"] = 5;
                values[valuesById[6] = "AUTOCOCKER_FIRE_MODE_CUSTOM_RAMPING"] = 6;
                return values;
            })();

            return v1;
        })();

        return autococker;
    })();

    snapshotpb.common = (function() {

        /**
         * Namespace common.
         * @memberof snapshotpb
         * @namespace
         */
        const common = {};

        common.v1 = (function() {

            /**
             * Namespace v1.
             * @memberof snapshotpb.common
             * @namespace
             */
            const v1 = {};

            /**
             * EyeMode enum.
             * @name snapshotpb.common.v1.EyeMode
             * @enum {number}
             * @property {number} EYE_MODE_UNSPECIFIED=0 EYE_MODE_UNSPECIFIED value
             * @property {number} EYE_MODE_OFF=1 EYE_MODE_OFF value
             * @property {number} EYE_MODE_REFLECTIVE=2 EYE_MODE_REFLECTIVE value
             * @property {number} EYE_MODE_BREAK_BEAM=3 EYE_MODE_BREAK_BEAM value
             */
            v1.EyeMode = (function() {
                const valuesById = {}, values = Object.create(valuesById);
                values[valuesById[0] = "EYE_MODE_UNSPECIFIED"] = 0;
                values[valuesById[1] = "EYE_MODE_OFF"] = 1;
                values[valuesById[2] = "EYE_MODE_REFLECTIVE"] = 2;
                values[valuesById[3] = "EYE_MODE_BREAK_BEAM"] = 3;
                return values;
            })();

            return v1;
        })();

        return common;
    })();

    return snapshotpb;
})();

export { $root as default };
