/*
 * Copyright The Athenz Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import React from 'react';
import Head from 'next/head';
import { withRouter } from 'next/router';
import Header from '../../../components/header/Header';
import UserDomains from '../../../components/domain/UserDomains';
import styled from '@emotion/styled';
import { colors } from '../../../components/denali/styles';
import Icon from '../../../components/denali/icons/Icon';
import Error from '../../_error';
import Link from 'next/link';
import PageUtils from '../../../components/utils/PageUtils';
import {
    selectAllDomainsList,
    selectUserDomains,
} from '../../../redux/selectors/domains';
import {
    getAllDomainsList,
    getUserDomainsList,
} from '../../../redux/thunks/domains';
import { connect } from 'react-redux';
import createCache from '@emotion/cache';
import { CacheProvider } from '@emotion/react';
import RequestUtils from '../../../components/utils/RequestUtils';
import { searchServices } from '../../../redux/thunks/services';

const AppContainerDiv = styled.div`
    align-items: stretch;
    flex-flow: row nowrap;
    height: 100%;
    display: flex;
    justify-content: flex-start;
`;

const MainContentDiv = styled.div`
    flex: 1 1 calc(100vh - 60px);
    overflow: hidden;
    font: 300 14px HelveticaNeue-Reg, Helvetica, Arial, sans-serif;
`;

const SearchContainerDiv = styled.div`
    align-items: stretch;
    flex: 1 1;
    height: calc(100vh - 60px);
    overflow: auto;
    display: flex;
    flex-direction: column;
`;

const SearchContentDiv = styled.div``;

const PageHeaderDiv = styled.div`
    background: #f5f8fe;
    padding: 20px 30px 0;
`;

const TitleDiv = styled.div`
    font: 600 20px HelveticaNeue-Reg, Helvetica, Arial, sans-serif;
    margin-bottom: 10px;
    display: flex;
`;

const ResultsDiv = styled.div`
    color: #3570f4;
    margin-bottom: 5px;
    font: 100 14px HelveticaNeue-Reg, Helvetica, Arial, sans-serif;
    cursor: pointer;
    padding: 0 30px 10px;
    display: flex;
`;

const StyledAnchor = styled.a`
    color: #3570f4;
    text-decoration: none;
    cursor: pointer;
`;

const DomainLogoDiv = styled.div`
    font-size: 1.25em;
    margin-right: 5px;
    vertical-align: text-bottom;
`;

const ResultsCountDiv = styled.div`
    color: #9a9a9a;
`;

const LineSeparator = styled.div`
    border-bottom: 1px solid #d5d5d5;
    margin-top: 10px;
    margin-bottom: 10px;
    width: 100%;
`;

export async function getServerSideProps(context) {
    let type = context.query.type;
    let reload = false;
    let error = null;
    return {
        props: {
            searchterm: context.query.searchterm,
            domainResults: [],
            type: type,
            error,
            reload,
            nonce: context.req && context.req.headers.rid,
            services: []
        },
    };
}

class PageSearchDetails extends React.Component {
    constructor(props) {
        super(props);
        this.cache = createCache({
            key: 'athenz',
            nonce: this.props.nonce,
        });
        this.state = {
            domainResults: props.domainResults,
            type: props.type,
            searchterm: props.searchterm,
            services: [],
        };
        this.findDomains = this.findDomains.bind(this);
        this.setupServicesSearchResults = this.setupServicesSearchResults.bind(this);
        this.performSearch = this.performSearch.bind(this);
    }

    componentDidMount() {
        // on first load of the component
        this.performSearch();
    }

    componentDidUpdate(prevProps, prevState, snapshot) {
        // if properties for this component change - e.g. on a new search - update state
        if (this.props.router.query.type !== prevProps.router.query.type
            || this.props.router.query.searchterm !== prevProps.router.query.searchterm
        ) {
            this.setState({
                type: this.props.router.query.type,
                searchterm: this.props.router.query.searchterm,
            }, this.performSearch);
        }
    }

    // DONE: works in general
    // TODO: fix searching for domain with services searchterm
    // update state or on component mount
    // peform search
        // domains
            // fetch domains - if not fetched
            // when done - filter ones that match TODO
            // set found in state TODO
        // services
            // fetch search
            // set found in state

    // render
        // based on search type
        // setup results for display

    performSearch() {
        // clear old search results before making new one
        // since domain search results are not stored in state
        // clear only services
        this.setState({
            services: [],
        }, () => { // perform search in callback
            switch (this.state.type) {
                case 'domain': {
                    if (!this.state.allDomainList) {
                        // searching for domains is done client-side
                        // so fetching domains can be done only once
                        Promise.all([this.props.getDomainList(), this.props.getAllDomainsList()]).catch((err) => {
                            this.showError(RequestUtils.fetcherErrorCheckHelper(err));
                        });
                    }
                } break;
                case 'service': {
                    // searching for services is done server-side
                    // so each time search must be done anew
                    this.props.searchServices(this.state.searchterm).
                    then((data) => {
                        this.setState({
                            services: data?.list ? data?.list : []
                        })
                    }).
                    catch((err) => {
                        this.showError(RequestUtils.fetcherErrorCheckHelper(err));
                    })
                } break;
            }
        });
    }

    findDomains(searchTerm) {
        const { allDomainList, userDomains } = this.props;
        let domainResults = [];
        if (allDomainList.length > 0 || userDomains.length > 0) {
            domainResults = allDomainList.filter((domain) => {
                return domain.name
                    .toLowerCase()
                    .includes(searchTerm.toLowerCase());
            });
            domainResults = domainResults.map((domain) => {
                let isUserDomain = false;
                let isAdminDomain = false;
                for (let userDomain of userDomains) {
                    if (userDomain.name === domain.name) {
                        isUserDomain = true;
                        if (userDomain.adminDomain) {
                            isAdminDomain = true;
                        }
                        break;
                    }
                }
                return {
                    name: domain.name,
                    userDomain: isUserDomain,
                    adminDomain: isAdminDomain,
                };
            });
        }
        return domainResults;
    }

    setupServicesSearchResults() {
        let items = [];
        this.state.services.forEach(function (service) {
            let serviceName = service.name;
            items.push(
                <ResultsDiv key={serviceName}>
                    <Link
                        href={PageUtils.rolePage(serviceName)}
                        passHref
                        legacyBehavior
                    >
                        <StyledAnchor>{serviceName}</StyledAnchor>
                    </Link>
                </ResultsDiv>
            );
        });
        return (
            <SearchContainerDiv>
                <SearchContentDiv>
                    <PageHeaderDiv>
                        <TitleDiv>Search Results</TitleDiv>
                        <ResultsCountDiv>
                            {items.length} Results
                        </ResultsCountDiv>
                        <LineSeparator />
                    </PageHeaderDiv>
                    {items}
                </SearchContentDiv>
            </SearchContainerDiv>
        );
    }

    setupDomainSearchResults(domainResults) {
        let items = [];
        domainResults.forEach(function (currentDomain) {
            let domain = currentDomain.name;
            let showIcon =
                currentDomain.adminDomain || currentDomain.userDomain;
            let iconType = currentDomain.adminDomain
                ? 'user-secure'
                : 'user-group';
            let icon;
            if (showIcon) {
                icon = (
                    <Icon
                        icon={iconType}
                        color={colors.black}
                        isLink
                        size={'1em'}
                        verticalAlign={'text-bottom'}
                    />
                );
            }
            items.push(
                <ResultsDiv key={domain}>
                    <DomainLogoDiv>{icon}</DomainLogoDiv>
                    <Link
                        href={PageUtils.rolePage(domain)}
                        passHref
                        legacyBehavior
                    >
                        <StyledAnchor>{domain}</StyledAnchor>
                    </Link>
                </ResultsDiv>
            );
        });
        return (
            <SearchContainerDiv>
                <SearchContentDiv>
                    <PageHeaderDiv>
                        <TitleDiv>Search Results</TitleDiv>
                        <ResultsCountDiv>
                            {items.length} Results
                        </ResultsCountDiv>
                        <LineSeparator />
                    </PageHeaderDiv>
                    {items}
                </SearchContentDiv>
            </SearchContainerDiv>
        );
    }

    render() {
        const { reload } = this.props;
        if (reload) {
            window.location.reload();
            return <div />;
        }
        if (this.props.error) {
            return <Error err={this.props.error} />;
        }

        let resultsToDisplay = '';
        // prepare results for display depending on search type
        if (this.state.type === 'domain') {
            let domainResult = this.findDomains(
                this.props.router.query.searchterm
            );
            resultsToDisplay = this.setupDomainSearchResults(domainResult);
        } else if (this.state.type === 'service') {
            resultsToDisplay = this.setupServicesSearchResults();
        }
        return (
            <CacheProvider value={this.cache}>
                <div data-testid='search'>
                    <Head>
                        <title>{this.state.searchterm} - Athenz</title>
                    </Head>
                    <Header showSearch={true} searchData={this.props.searchterm} />
                    <MainContentDiv>
                        <AppContainerDiv>
                            {resultsToDisplay}
                            <UserDomains />
                        </AppContainerDiv>
                    </MainContentDiv>
                </div>
            </CacheProvider>
        );
    }
}

const mapStateToProps = (state, props) => {
    return {
        ...props,
        allDomainList: selectAllDomainsList(state),
        userDomains: selectUserDomains(state),
    };
};

const mapDispatchToProps = (dispatch) => ({
    getAllDomainsList: () => dispatch(getAllDomainsList()),
    getDomainList: () => dispatch(getUserDomainsList()),
    searchServices: (subString) => dispatch(searchServices(subString)),
});

export default connect(
    mapStateToProps,
    mapDispatchToProps
)(withRouter(PageSearchDetails));
