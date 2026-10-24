package org.docToolchain.configuration

import spock.lang.Specification

class ConfigServiceSpec extends Specification {

    ConfigService configService

    def setup() {
        ConfigObject config = new ConfigObject()
        config.docDir = "src/docs"
        config.confluence = [
            api : 'https://my.confluence/rest/api/',
            spaceKey: 'asciidoc',
            proxy: [
                host: 'proxy.example.com',
                port: 8080,
                schema: 'https'
            ]
        ]
        configService = new ConfigService(config)
    }

    def "test get simple config"() {
        when: 'i try to access a simple config property'
            def property = this.configService.getConfigProperty("docDir")
        then: 'the config property is returned'
            property == "src/docs"
            noExceptionThrown()
    }

    def "test get nested config"() {
        when: 'i try to access a nested config property'
            def property = configService.getConfigProperty("confluence.api")
        then: 'the config property is returned'
            property == "https://my.confluence/rest/api/"
            noExceptionThrown()
    }

    def "test get nested config tree using getConfigProperty"() {
        when: 'i try to access a nested config property that is a tree'
            def property = configService.getConfigProperty("confluence.proxy")
        then: 'a null value is returned'
            property == null
            noExceptionThrown()
    }

    def "test get first-level nested config property"() {
        when: 'i try to access a first-level config tree using getConfigProperty'
            def property = configService.getConfigProperty("confluence")
        then: 'the config tree is returned'
            property.size() == 3
            property.api == "https://my.confluence/rest/api/"
            property.spaceKey == "asciidoc"
            noExceptionThrown()
    }

    def "test get first-level nested config tree"() {
        when: 'i try to access a first-level config tree using getFlatConfigSubTree'
            def property = configService.getFlatConfigSubTree("confluence")
        then: 'the config tree is returned'
            property.size() == 5
            property.api == "https://my.confluence/rest/api/"
            property.spaceKey == "asciidoc"
            property["proxy.host"] == "proxy.example.com"
            property["proxy.port"] == 8080
            property["proxy.schema"] == "https"
            noExceptionThrown()
    }

    def "test get second-level nested config tree"() {
        when: 'i try to access a second-level config tree'
            def property = configService.getFlatConfigSubTree("confluence.proxy")
        then: 'the config tree is returned'
            property.size() == 3
            property.host == "proxy.example.com"
            property.port == 8080
            property.schema == "https"
            noExceptionThrown()
    }

    def "test try to get non-existing config using getConfigProperty"() {
        when: 'i try to access a non-existing property'
            def property = configService.getConfigProperty("non-sense")
        then: 'a null value is returned'
            property == null
            noExceptionThrown()
    }

    def "test try to get non-existing config using getFlatConfigSubTree"() {
        when: 'i try to access a non-existing property'
            def property = configService.getFlatConfigSubTree("non-sense")
        then: 'an empty map is returned'
            property == Collections.EMPTY_MAP
            noExceptionThrown()
    }

    def 'a raw lookup tells a configured false from an unwritten one'() {
        given:
            def config = new ConfigObject()
            config.confluence = [useV1Api: false, spaceKey: 'SPACE']
            def service = new ConfigService(config)

        expect: 'Groovy truth calls both of these empty, so the usual lookup cannot tell them apart'
            service.getConfigProperty('confluence.useV1Api') == null
            service.getConfigProperty('confluence.absent') == null

        and: 'the raw lookup keeps the value that was written'
            service.getRawConfigProperty('confluence.useV1Api') == false
            service.getRawConfigProperty('confluence.absent') == null
    }

    def 'a raw lookup returns the other falsy values as written too'() {
        given:
            def config = new ConfigObject()
            config.confluence = [rateLimit: 0, pagePrefix: '']
            def service = new ConfigService(config)

        expect:
            service.getRawConfigProperty('confluence.rateLimit') == 0
            service.getRawConfigProperty('confluence.pagePrefix') == ''
    }

    def 'a switch with a default of on is only off where it says so'() {
        given: '''A setting arrives from a YAML file, a Groovy file or the command line, and only
                  the first of those necessarily carries a type.'''
            def config = new ConfigObject()
            config.confluence = [spelledOut: 'false', written: false, on: true]
            def service = new ConfigService(config)

        expect:
            service.isSwitchedOn('confluence.written', true) == false
            service.isSwitchedOn('confluence.spelledOut', true) == false
            service.isSwitchedOn('confluence.on', true) == true

        and: 'an absence is the default, which is the whole point of passing one'
            service.isSwitchedOn('confluence.absent', true) == true
            service.isSwitchedOn('confluence.absent', false) == false
    }
}
